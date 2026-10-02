"""POST /api/v1/sync/push (Doc 2 s5.1, s5.2, s6, I-2, I-3, I-11; Doc 1 s4.1, s5, s6).

Covers AT-1, AT-3, AT-7 through the API, partial payment, overpayment, walk-in rules, mixed
good and bad records, a worker pushing another device's number, retryable and the 200 limit.
"""
import uuid

import pytest
from rest_framework.test import APIClient

from apps.accounts.models import Device
from apps.catalog.models import CustomerType
from apps.catalog.services import create_customer
from apps.sales import selectors, services
from apps.sales.models import Invoice, InvoiceItem, Payment, PaymentAllocation
from apps.sync.models import SyncBatchLog
from tests.core_helpers import make_user, token_client
from tests.test_sales_helpers import build_world, invoice_payload, payment_payload, push_body

pytestmark = pytest.mark.django_db
URL = "/api/v1/sync/push"


@pytest.fixture
def world():
    return build_world()


def push(world, **kwargs):
    response = world.client.post(URL, push_body(world, **kwargs), format="json")
    assert response.status_code == 200, response.content
    return response.json()


def statuses(body):
    return [r["status"] for r in body["results"]]


def balance(world):
    from apps.catalog.selectors import get_customer

    return selectors.customer_balance(get_customer(world.customer.id))


# ---- access -----------------------------------------------------------------------------


def test_admin_gets_403_and_anonymous_401(world):
    admin = make_user("boss", role="admin")
    response = token_client(admin).post(URL, push_body(world), format="json")
    assert response.status_code == 403
    assert set(response.json()) == {"code", "message", "details"}
    assert APIClient().post(URL, push_body(world), format="json").status_code == 401


def test_response_shape_and_no_cost_keys(world):
    body = push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    assert set(body) == {"results", "server_time", "catalog_cursor"}
    assert isinstance(body["catalog_cursor"], int)

    def keys(node):
        if isinstance(node, dict):
            for k, v in node.items():
                yield k
                yield from keys(v)
        elif isinstance(node, list):
            for v in node:
                yield from keys(v)

    assert not [k for k in keys(body) if any(w in k for w in ("cost", "profit", "expense"))]


# ---- AT-3 idempotency ---------------------------------------------------------------------


def test_at3_push_twice_stores_one_record(world):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    pay = payment_payload(400, invoice_id=inv["id"])
    first = push(world, invoices=[inv], payments=[pay])
    second = push(world, invoices=[inv], payments=[pay])
    assert statuses(first) == ["accepted", "accepted"]
    assert statuses(second) == ["duplicate", "duplicate"]
    assert second["results"][1]["allocated"] == first["results"][1]["allocated"]
    assert Invoice.objects.count() == 1 and InvoiceItem.objects.count() == 1
    assert Payment.objects.count() == 1 and PaymentAllocation.objects.count() == 1
    assert balance(world) == 600


def test_same_record_twice_in_one_push_is_one_record(world):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    body = push(world, invoices=[inv, inv])
    assert statuses(body) == ["accepted", "duplicate"]
    assert Invoice.objects.count() == 1


# ---- AT-1 and AT-7 ------------------------------------------------------------------------


def test_at1_ledger_example(world):
    inv1 = invoice_payload(world, "MAM-W1-0001", 12000, day=3)
    inv2 = invoice_payload(world, "MAM-W1-0002", 9000, day=10)
    inv3 = invoice_payload(world, "MAM-W1-0003", 6000, day=20)
    push(world, invoices=[inv1, inv2])
    pay = push(world, payments=[payment_payload(15000, customer=world.customer, day=12)])
    assert pay["results"][0]["allocated"] == [
        {"invoice_id": inv1["id"], "amount_cents": 12000},
        {"invoice_id": inv2["id"], "amount_cents": 3000},
    ]
    push(world, invoices=[inv3])
    assert balance(world) == 12000  # $120.00
    i1, i2 = selectors.get_invoice(inv1["id"]), selectors.get_invoice(inv2["id"])
    assert i1.total_cents - selectors.invoice_paid_cents(i1) == 0  # invoice 1 paid
    assert i2.total_cents - selectors.invoice_paid_cents(i2) == 6000  # invoice 2 owes $60
    assert selectors.open_invoices(world.customer.__class__.objects.get(pk=world.customer.pk)) == [
        (i2.pk, 6000),
        (selectors.get_invoice(inv3["id"]).pk, 6000),
    ]


def test_at1_all_in_one_push_gives_the_same_closing_balance(world):
    inv1 = invoice_payload(world, "MAM-W1-0001", 12000, day=3)
    inv2 = invoice_payload(world, "MAM-W1-0002", 9000, day=10)
    inv3 = invoice_payload(world, "MAM-W1-0003", 6000, day=20)
    body = push(
        world, invoices=[inv1, inv2, inv3],
        payments=[payment_payload(15000, customer=world.customer, day=12)],
    )
    assert statuses(body) == ["accepted"] * 4
    assert balance(world) == 12000


def test_at7_void_is_excluded_from_balance_and_keeps_its_number(world):
    inv = invoice_payload(world, "MAM-W1-0001", 5000)
    push(world, invoices=[inv], payments=[])
    assert balance(world) == 5000
    admin = make_user("boss", role="admin")
    services.void_invoice(selectors.get_invoice(inv["id"]), actor=admin, reason="Mistake")
    assert balance(world) == 0
    voided = selectors.get_invoice(inv["id"])
    assert voided.status == "void" and voided.number == "MAM-W1-0001"
    # the number is still taken: it cannot be reused (Doc 1 s5.2)
    again = push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 100)])
    assert again["results"][0]["reason"]["code"] == "duplicate_number"


# ---- partial payment and overpayment ------------------------------------------------------


def test_partial_payment(world):
    inv = invoice_payload(world, "MAM-W1-0001", 10000)
    body = push(world, invoices=[inv], payments=[payment_payload(4000, invoice_id=inv["id"])])
    assert body["results"][1]["allocated"] == [{"invoice_id": inv["id"], "amount_cents": 4000}]
    assert balance(world) == 6000
    more = push(world, payments=[payment_payload(1500, invoice_id=inv["id"], day=13)])
    assert more["results"][0]["allocated"] == [{"invoice_id": inv["id"], "amount_cents": 1500}]
    assert balance(world) == 4500


def test_overpayment_becomes_credit_on_account(world):
    inv = invoice_payload(world, "MAM-W1-0001", 10000)
    body = push(world, invoices=[inv], payments=[payment_payload(15000, invoice_id=inv["id"])])
    assert body["results"][1]["allocated"] == [{"invoice_id": inv["id"], "amount_cents": 10000}]
    assert balance(world) == -5000  # credit on account, negative (Doc 1 A-4)
    assert sum(a.amount_cents for a in PaymentAllocation.objects.all()) <= 15000  # I-5
    later = push(world, invoices=[invoice_payload(world, "MAM-W1-0002", 8000, day=20)])
    assert statuses(later) == ["accepted"]
    assert balance(world) == 3000


def test_payment_with_no_invoice_pays_the_old_balance(world):
    inv = invoice_payload(world, "MAM-W1-0001", 10000)
    push(world, invoices=[inv])
    body = push(world, payments=[payment_payload(2500, customer=world.customer)])
    assert body["results"][0]["allocated"] == [{"invoice_id": inv["id"], "amount_cents": 2500}]


def test_opening_balance_counts(world):
    from apps.catalog.services import update_customer

    admin = make_user("boss", role="admin")
    update_customer(world.customer, actor=admin, opening_balance_cents=7000)
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    assert balance(world) == 8000


# ---- walk-in ------------------------------------------------------------------------------


def test_walk_in_fully_paid_is_stored_and_never_allocated(world):
    inv = invoice_payload(world, "MAM-W1-0001", 2000, customer=None)
    pay = payment_payload(2000, invoice_id=inv["id"])
    body = push(world, invoices=[inv], payments=[pay])
    assert statuses(body) == ["accepted", "accepted"]
    assert body["results"][1]["allocated"] == []
    stored = Payment.objects.get()
    assert stored.customer_id is None and PaymentAllocation.objects.count() == 0
    assert Invoice.objects.get().customer_id is None


@pytest.mark.parametrize("paid", [None, 1999, 2001])
def test_walk_in_must_be_paid_exactly_in_full(world, paid):
    inv = invoice_payload(world, "MAM-W1-0001", 2000, customer=None)
    payments = [payment_payload(paid, invoice_id=inv["id"])] if paid else []
    body = push(world, invoices=[inv], payments=payments)
    assert body["results"][0]["status"] == "rejected"
    assert body["results"][0]["reason"]["code"] == "walkin_not_fully_paid"
    assert body["results"][0]["reason"]["retryable"] is False
    assert Invoice.objects.count() == 0
    if payments:  # its payment cannot be stored without the invoice, and it will never succeed
        assert body["results"][1]["status"] == "rejected"
        assert body["results"][1]["reason"]["retryable"] is False


def test_walk_in_split_payments_that_add_up_are_accepted(world):
    inv = invoice_payload(world, "MAM-W1-0001", 2000, customer=None)
    body = push(
        world, invoices=[inv],
        payments=[payment_payload(1500, invoice_id=inv["id"]),
                  payment_payload(500, invoice_id=inv["id"], method="zelle")],
    )
    assert statuses(body) == ["accepted"] * 3


# ---- mixed good and bad -------------------------------------------------------------------


def test_one_bad_record_never_blocks_the_others(world):
    good1 = invoice_payload(world, "MAM-W1-0001", 1000)
    bad = invoice_payload(world, "MAM-W1-0002", 1000)
    bad["total_cents"] = 999  # I-2
    good2 = invoice_payload(world, "MAM-W1-0003", 3000)
    pay_good = payment_payload(500, invoice_id=good1["id"])
    pay_bad = payment_payload(-5, invoice_id=good2["id"])
    body = push(world, invoices=[good1, bad, good2], payments=[pay_bad, pay_good])
    assert statuses(body) == ["accepted", "rejected", "accepted", "rejected", "accepted"]
    assert body["results"][1]["reason"]["code"] == "total_mismatch"
    assert body["results"][3]["reason"]["code"] == "validation_error"
    assert Invoice.objects.count() == 2 and Payment.objects.count() == 1
    assert [r["id"] for r in body["results"]][:3] == [good1["id"], bad["id"], good2["id"]]


def test_malformed_records_are_rejected_not_crashes(world):
    body = push(world, invoices=["nope", {"id": "not-a-uuid"}, {}], payments=[None, 5])
    assert statuses(body) == ["rejected"] * 5
    assert all(r["reason"]["retryable"] is False for r in body["results"])


# ---- validation: numbers, devices, totals, quantities ------------------------------------


def test_worker_cannot_push_another_devices_number(world):
    other = make_user("w2user")
    Device.objects.create(code="W2", user=other)
    body = push(world, invoices=[invoice_payload(world, "MAM-W2-0001", 1000)])
    assert body["results"][0]["status"] == "rejected"
    assert body["results"][0]["reason"]["code"] == "wrong_device"
    assert Invoice.objects.count() == 0


def test_device_code_must_be_the_workers_own(world):
    other = make_user("w2user")
    Device.objects.create(code="W2", user=other)
    for code in ("W2", "W9"):
        payload = push_body(world, invoices=[invoice_payload(world, f"MAM-{code}-0001", 1000)])
        payload["device_code"] = code
        response = world.client.post(URL, payload, format="json")
        assert response.status_code == 400
        assert response.json()["code"] == "validation_error"
    assert Invoice.objects.count() == 0


@pytest.mark.parametrize(
    "number", ["MAM-W1-42", "mam-w1-0042", "INV-W1-0042", "MAM-W1-", "", None, 7]
)
def test_bad_number_format(world, number):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    inv["number"] = number
    body = push(world, invoices=[inv])
    assert body["results"][0]["reason"]["code"] == "invalid_number"


def test_duplicate_number_with_a_different_uuid(world):
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    body = push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 2000)])
    reason = body["results"][0]["reason"]
    assert body["results"][0]["status"] == "rejected"
    assert reason["code"] == "duplicate_number" and reason["retryable"] is False
    assert Invoice.objects.count() == 1


def test_total_must_equal_sum_of_lines(world):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    inv["items"].append({"id": str(uuid.uuid4()), "product_id": str(world.fresh.id),
                         "qty_packets": 2, "unit_price_cents": 250})
    inv["total_cents"] = 1000  # lines add up to 1500
    assert push(world, invoices=[inv])["results"][0]["reason"]["code"] == "total_mismatch"
    inv["total_cents"] = 1500
    assert push(world, invoices=[inv])["results"][0]["status"] == "accepted"


@pytest.mark.parametrize("field,value", [
    ("qty_packets", 0), ("qty_packets", -1), ("qty_packets", 1.5), ("qty_packets", "2"),
    ("qty_packets", True), ("unit_price_cents", 0), ("unit_price_cents", -5),
    ("unit_price_cents", 2.5), ("unit_price_cents", None),
])
def test_zero_negative_and_non_integer_item_values_are_rejected(world, field, value):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    inv["items"][0][field] = value
    body = push(world, invoices=[inv])
    assert body["results"][0]["status"] == "rejected"
    assert body["results"][0]["reason"]["retryable"] is False
    assert Invoice.objects.count() == 0


def test_invoice_total_zero_negative_or_no_items_rejected(world):
    zero = invoice_payload(world, "MAM-W1-0001", 1000)
    zero["total_cents"] = 0
    none = invoice_payload(world, "MAM-W1-0002", 1000)
    none["items"] = []
    none["total_cents"] = 0
    neg = invoice_payload(world, "MAM-W1-0003", 1000)
    neg["total_cents"] = -1000
    body = push(world, invoices=[zero, none, neg])
    assert statuses(body) == ["rejected"] * 3


def test_unknown_customer_and_product(world):
    ghost = invoice_payload(world, "MAM-W1-0001", 1000)
    ghost["customer_id"] = str(uuid.uuid4())
    bad_product = invoice_payload(world, "MAM-W1-0002", 1000)
    bad_product["items"][0]["product_id"] = str(uuid.uuid4())
    body = push(world, invoices=[ghost, bad_product])
    assert [r["reason"]["code"] for r in body["results"]] == ["unknown_customer", "unknown_product"]


def test_price_snapshot_is_stored_as_sent_not_repriced(world):
    inv = invoice_payload(world, "MAM-W1-0001", 0, qty=3, price=777)
    push(world, invoices=[inv])
    item = InvoiceItem.objects.get()
    assert item.unit_price_cents == 777 and item.line_total_cents == 2331
    assert Invoice.objects.get().total_cents == 2331


def test_name_snapshots_are_server_side(world):
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    invoice = Invoice.objects.get()
    assert invoice.customer_name == "Spice Garden" and invoice.customer_type == "Restaurant"
    assert invoice.worker_id == world.worker.id and invoice.device_id == world.device.id
    assert InvoiceItem.objects.get().product_name == world.chapathi.name


def test_inactive_customer_is_still_accepted(world):
    from apps.catalog.services import update_customer

    update_customer(world.customer, is_active=False)
    body = push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    assert statuses(body) == ["accepted"]


# ---- payments -----------------------------------------------------------------------------


def test_payment_needs_customer_or_invoice(world):
    body = push(world, payments=[payment_payload(500)])
    reason = body["results"][0]["reason"]
    assert reason["code"] == "missing_target" and reason["retryable"] is False


def test_payment_before_its_invoice_is_retryable(world):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    pay = payment_payload(500, invoice_id=inv["id"])
    first = push(world, payments=[pay])
    assert first["results"][0]["status"] == "rejected"
    assert first["results"][0]["reason"] == {
        "code": "invoice_not_found", "message": first["results"][0]["reason"]["message"],
        "retryable": True,
    }
    assert Payment.objects.count() == 0
    second = push(world, invoices=[inv], payments=[pay])  # the device resends both
    assert statuses(second) == ["accepted", "accepted"]


def test_payment_for_a_rejected_invoice_in_the_same_push_is_not_retryable(world):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    inv["total_cents"] = 1
    body = push(world, invoices=[inv], payments=[payment_payload(500, invoice_id=inv["id"])])
    assert body["results"][1]["status"] == "rejected"
    assert body["results"][1]["reason"]["retryable"] is False


def test_payment_customer_must_match_the_invoice(world):
    restaurant = CustomerType.objects.get(name="Restaurant")
    other = create_customer(name="Other", type=restaurant, payment_mode="credit")
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    push(world, invoices=[inv])
    body = push(world, payments=[payment_payload(100, invoice_id=inv["id"], customer=other)])
    assert body["results"][0]["reason"]["code"] == "customer_mismatch"
    ok = push(world, payments=[payment_payload(100, invoice_id=inv["id"], customer=world.customer)])
    assert statuses(ok) == ["accepted"]


def test_payment_unknown_customer(world):
    ghost = payment_payload(100)
    ghost["customer_id"] = str(uuid.uuid4())
    assert push(world, payments=[ghost])["results"][0]["reason"]["code"] == "unknown_customer"


@pytest.mark.parametrize("amount", [0, -100, 1.5, "100", None, True])
def test_payment_amount_must_be_a_positive_integer(world, amount):
    pay = payment_payload(100, customer=world.customer)
    pay["amount_cents"] = amount
    body = push(world, payments=[pay])
    assert body["results"][0]["status"] == "rejected"
    assert Payment.objects.count() == 0


def test_payment_method_and_note(world):
    bad = payment_payload(100, customer=world.customer, method="bitcoin")
    other_no_note = payment_payload(100, customer=world.customer, method="other")
    other_note = payment_payload(100, customer=world.customer, method="other", note="money order")
    body = push(world, payments=[bad, other_no_note, other_note])
    assert statuses(body) == ["rejected", "rejected", "accepted"]
    for method in ("cash", "zelle", "check", "card"):
        ok = push(world, payments=[payment_payload(100, customer=world.customer, method=method)])
        assert statuses(ok) == ["accepted"]


def test_receipt_number_is_stored_as_sent(world):
    body = push(world, payments=[
        payment_payload(100, customer=world.customer, receipt_number="R-W1-0007")])
    assert statuses(body) == ["accepted"]
    assert Payment.objects.get().receipt_number == "R-W1-0007"


def test_void_invoice_is_skipped_by_allocation(world):
    inv1 = invoice_payload(world, "MAM-W1-0001", 5000, day=3)
    inv2 = invoice_payload(world, "MAM-W1-0002", 5000, day=4)
    push(world, invoices=[inv1, inv2])
    admin = make_user("boss", role="admin")
    services.void_invoice(selectors.get_invoice(inv1["id"]), actor=admin, reason="dup")
    body = push(world, payments=[payment_payload(5000, customer=world.customer)])
    assert body["results"][0]["allocated"] == [{"invoice_id": inv2["id"], "amount_cents": 5000}]


# ---- returns, limit, log -------------------------------------------------------------------


def test_returns_are_rejected_with_a_clear_reason(world):
    rid = str(uuid.uuid4())
    body = push(world, returns=[{"id": rid, "product_id": str(world.chapathi.id)}])
    result = body["results"][0]
    assert result["type"] == "return" and result["id"] == rid
    assert result["status"] == "rejected"
    assert result["reason"]["code"] == "returns_not_supported"
    assert result["reason"]["retryable"] is False
    assert "not supported" in result["reason"]["message"].lower()


def test_more_than_200_records_is_a_400_with_the_error_body(world):
    invoices = [invoice_payload(world, f"MAM-W1-{i:04d}", 100) for i in range(1, 202)]
    response = world.client.post(URL, push_body(world, invoices=invoices), format="json")
    assert response.status_code == 400
    assert set(response.json()) == {"code", "message", "details"}
    assert response.json()["code"] == "validation_error"
    assert Invoice.objects.count() == 0


def test_exactly_200_records_is_allowed(world):
    payments = [payment_payload(100) for _ in range(150)]  # all rejected quickly: no target
    invoices = [invoice_payload(world, f"MAM-W1-{i:04d}", 100) for i in range(1, 51)]
    body = push(world, invoices=invoices, payments=payments)
    assert len(body["results"]) == 200
    assert statuses(body).count("accepted") == 50


@pytest.mark.parametrize("payload", [
    {}, {"device_code": "W1"},
    {"device_code": "W1", "invoices": "x", "payments": [], "returns": []},
    {"device_code": 5, "invoices": [], "payments": [], "returns": []}, [],
])
def test_malformed_request_is_400(world, payload):
    response = world.client.post(URL, payload, format="json")
    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"


def test_each_push_writes_one_batch_log_row(world):
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    push(world)
    logs = list(SyncBatchLog.objects.order_by("received_at"))
    assert len(logs) == 2 and logs[0].device_id == world.device.id
    assert logs[0].counts_json["invoice"]["accepted"] == 1
