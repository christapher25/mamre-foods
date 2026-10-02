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
