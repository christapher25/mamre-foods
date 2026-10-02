"""Reviewer findings for push (Doc 2 s5.1, s5.2, I-1, I-11; Doc 1 s5, s6)."""
import pytest
from django.db import DataError

from apps.sales import selectors
from apps.sales.models import Invoice, Payment
from apps.sync import records
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


def assert_validation_reject(result):
    assert result["status"] == "rejected"
    assert result["reason"]["code"] == "validation_error"
    assert result["reason"]["retryable"] is False


# ---- finding 4: bounds ----------------------------------------------------------------------


def test_bounds_are_the_documented_ones():
    assert records.MAX_CENTS == 1_000_000_000
    assert records.MAX_QTY == 100_000


def test_huge_quantity_price_and_amount_are_rejected_not_500(world):
    huge_qty = invoice_payload(world, "MAM-W1-0001", 0, qty=1, price=100)
    huge_qty["items"][0]["qty_packets"] = 10**30
    huge_price = invoice_payload(world, "MAM-W1-0002", 0, qty=1, price=100)
    huge_price["items"][0]["unit_price_cents"] = 10**30
    huge_total = invoice_payload(world, "MAM-W1-0003", 100)
    huge_total["total_cents"] = 10**30
    good = invoice_payload(world, "MAM-W1-0004", 1000)
    huge_pay = payment_payload(10**30, customer=world.customer)
    good_pay = payment_payload(500, invoice_id=good["id"])
    body = push(world, invoices=[huge_qty, huge_price, huge_total, good],
                payments=[huge_pay, good_pay])
    assert statuses(body) == ["rejected"] * 3 + ["accepted", "rejected", "accepted"]
    for index in (0, 1, 2, 4):
        assert_validation_reject(body["results"][index])
    assert Invoice.objects.count() == 1 and Payment.objects.count() == 1


@pytest.mark.parametrize("field,value", [
    ("qty_packets", 100_001), ("unit_price_cents", 1_000_000_001),
])
def test_just_over_the_item_bounds_is_rejected(world, field, value):
    inv = invoice_payload(world, "MAM-W1-0001", 100)
    inv["items"][0][field] = value
    inv["total_cents"] = inv["items"][0]["qty_packets"] * inv["items"][0]["unit_price_cents"]
    assert_validation_reject(push(world, invoices=[inv])["results"][0])


def test_total_over_the_bound_is_rejected_even_when_lines_add_up(world):
    inv = invoice_payload(world, "MAM-W1-0001", 0, qty=100_000, price=10_001)  # 1,000,100,000
    assert inv["total_cents"] == 1_000_100_000
    assert_validation_reject(push(world, invoices=[inv])["results"][0])
    assert Invoice.objects.count() == 0


def test_values_exactly_on_the_bounds_are_accepted(world):
    inv = invoice_payload(world, "MAM-W1-0001", 0, qty=100_000, price=10_000)  # 1,000,000,000
    pay = payment_payload(1_000_000_000, customer=world.customer)
    big_price = invoice_payload(world, "MAM-W1-0002", 0, qty=1, price=1_000_000_000)
    body = push(world, invoices=[inv, big_price], payments=[pay])
    assert statuses(body) == ["accepted"] * 3


@pytest.mark.parametrize("error", [DataError("value too large"), OverflowError("too big")])
def test_database_and_overflow_errors_reject_only_that_record(world, monkeypatch, error):
    from apps.sales import services as sales_services

    real_invoice, real_payment = sales_services.store_invoice, sales_services.store_payment
    bad_number = "MAM-W1-0002"

    def flaky_invoice(*, worker, device, data):
        if data["number"] == bad_number:
            raise error
        return real_invoice(worker=worker, device=device, data=data)

    def flaky_payment(*, worker, device, data):
        if data["amount_cents"] == 777:
            raise error
        return real_payment(worker=worker, device=device, data=data)

    monkeypatch.setattr(sales_services, "store_invoice", flaky_invoice)
    monkeypatch.setattr(sales_services, "store_payment", flaky_payment)
    good = invoice_payload(world, "MAM-W1-0001", 1000)
    bad = invoice_payload(world, bad_number, 1000)
    body = push(world, invoices=[good, bad],
                payments=[payment_payload(777, customer=world.customer),
                          payment_payload(100, invoice_id=good["id"])])
    assert statuses(body) == ["accepted", "rejected", "rejected", "accepted"]
    assert_validation_reject(body["results"][1])
    assert_validation_reject(body["results"][2])
    assert selectors.get_invoice(good["id"]) is not None


# ---- finding 5: a walk-in is validated with ALL its payments before anything is stored -------


def stored_counts():
    return Invoice.objects.count(), Payment.objects.count()


def walk_in(world, total=2000, number="MAM-W1-0001"):
    return invoice_payload(world, number, total, customer=None)


def test_walk_in_payment_that_fails_validation_does_not_count(world):
    """method other without a note is invalid, so only 1000 of 2000 counts."""
    inv = walk_in(world)
    ok = payment_payload(1000, invoice_id=inv["id"])
    no_note = payment_payload(1000, invoice_id=inv["id"], method="other")
    body = push(world, invoices=[inv], payments=[ok, no_note])
    assert statuses(body) == ["rejected"] * 3
    assert body["results"][0]["reason"]["code"] == "walkin_not_fully_paid"
    assert body["results"][1]["reason"]["code"] == "invoice_rejected"
    assert body["results"][1]["reason"]["retryable"] is False
    assert body["results"][2]["reason"]["code"] == "validation_error"
    assert stored_counts() == (0, 0)


def test_walk_in_payment_naming_a_customer_does_not_count(world):
    inv = walk_in(world)
    ok = payment_payload(1000, invoice_id=inv["id"])
    mismatch = payment_payload(1000, invoice_id=inv["id"], customer=world.customer)
    body = push(world, invoices=[inv], payments=[ok, mismatch])
    assert body["results"][0]["reason"]["code"] == "walkin_not_fully_paid"
    assert body["results"][1]["reason"]["code"] == "invoice_rejected"
    # the invoice was rejected, so its payments are too: invoice_rejected names the root cause
    assert body["results"][2]["reason"]["code"] == "invoice_rejected"
    assert body["results"][2]["reason"]["retryable"] is False
    assert stored_counts() == (0, 0)


def test_walk_in_payment_naming_an_unknown_customer_does_not_count(world):
    import uuid

    inv = walk_in(world)
    ghost = payment_payload(2000, invoice_id=inv["id"])
    ghost["customer_id"] = str(uuid.uuid4())
    body = push(world, invoices=[inv], payments=[ghost])
    assert body["results"][0]["reason"]["code"] == "walkin_not_fully_paid"
    assert body["results"][1]["reason"]["code"] == "invoice_rejected"
    assert stored_counts() == (0, 0)


@pytest.mark.parametrize("amounts", [[1999], [500, 1000], [2001], [1500, 1000]])
def test_walk_in_under_and_over_payment_store_nothing(world, amounts):
    inv = walk_in(world)
    payments = [payment_payload(a, invoice_id=inv["id"]) for a in amounts]
    body = push(world, invoices=[inv], payments=payments)
    assert statuses(body) == ["rejected"] * (1 + len(amounts))
    assert body["results"][0]["reason"]["code"] == "walkin_not_fully_paid"
    assert all(r["reason"]["code"] == "invoice_rejected" for r in body["results"][1:])
    assert stored_counts() == (0, 0)


def test_walk_in_with_an_invalid_payment_that_still_adds_up_is_accepted_without_it(world):
    inv = walk_in(world)
    good = payment_payload(2000, invoice_id=inv["id"])
    junk = payment_payload(50, invoice_id=inv["id"], method="other")  # invalid, ignored
    body = push(world, invoices=[inv], payments=[good, junk])
    assert statuses(body) == ["accepted", "accepted", "rejected"]
    assert stored_counts() == (1, 1)


def test_walk_in_with_a_rejected_neighbour_does_not_block_other_records(world):
    bad = walk_in(world, number="MAM-W1-0001")
    good_inv = walk_in(world, number="MAM-W1-0002")
    credit = invoice_payload(world, "MAM-W1-0003", 1000)
    body = push(world, invoices=[bad, good_inv, credit],
                payments=[payment_payload(1, invoice_id=bad["id"]),
                          payment_payload(2000, invoice_id=good_inv["id"])])
    assert statuses(body) == ["rejected", "accepted", "accepted", "rejected", "accepted"]
    assert stored_counts() == (2, 1)


# ---- finding 7: the duplicate-payment fast path ---------------------------------------------


def test_duplicate_payment_is_answered_from_storage_without_storing_again(world, monkeypatch):
    """Fails if the fast path in _push_payment is removed: the second send must never reach
    store_payment (which would take a counter version and try to allocate again)."""
    from apps.sales import services as sales_services
    from apps.sales.models import PaymentAllocation

    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    first_pay = payment_payload(400, invoice_id=inv["id"])
    first = push(world, invoices=[inv], payments=[first_pay])
    assert first["results"][1]["status"] == "accepted"
    original = first["results"][1]["allocated"]
    assert original == [{"invoice_id": inv["id"], "amount_cents": 400}]
    # another payment lands, so a fresh allocation of the first would now look different
    push(world, payments=[payment_payload(300, invoice_id=inv["id"], day=13)])
    allocations_before = PaymentAllocation.objects.count()
    cursor_before = selectors.current_sales_cursor()

    def must_not_be_called(**kwargs):
        raise AssertionError("store_payment was called for a payment that is already stored")

    monkeypatch.setattr(sales_services, "store_payment", must_not_be_called)
    second = push(world, payments=[first_pay])

    assert second["results"][0]["status"] == "duplicate"
    assert second["results"][0]["allocated"] == original
    assert PaymentAllocation.objects.count() == allocations_before == 2
    assert Payment.objects.count() == 2
    assert selectors.current_sales_cursor() == cursor_before  # no version was taken


def test_duplicate_invoice_is_answered_from_storage_without_storing_again(world, monkeypatch):
    from apps.sales import services as sales_services

    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    push(world, invoices=[inv])
    cursor_before = selectors.current_sales_cursor()

    def must_not_be_called(**kwargs):
        raise AssertionError("store_invoice was called for an invoice that is already stored")

    monkeypatch.setattr(sales_services, "store_invoice", must_not_be_called)
    body = push(world, invoices=[inv])
    assert statuses(body) == ["duplicate"]
    assert selectors.current_sales_cursor() == cursor_before
