"""Pure rules and services for allocation and balance (Doc 1 s6.2, s6.3, s6.5; Doc 2 I-5, I-6)."""
import pytest

from apps.sales import rules, selectors, services
from apps.sales.models import PaymentAllocation
from tests.test_sales_helpers import build_world, utc

# ---- pure, no database ---------------------------------------------------------------------


def test_allocate_oldest_first_and_spills():
    allocs, rest = rules.allocate(15000, [("a", 12000), ("b", 9000)])
    assert allocs == [("a", 12000), ("b", 3000)] and rest == 0


def test_allocate_partial_leaves_nothing_over():
    assert rules.allocate(4000, [("a", 10000)]) == ([("a", 4000)], 0)


def test_allocate_overpayment_remainder_is_credit():
    assert rules.allocate(15000, [("a", 10000)]) == ([("a", 10000)], 5000)


def test_allocate_with_nothing_open_is_all_credit():
    assert rules.allocate(500, []) == ([], 500)


def test_allocate_skips_settled_invoices_and_never_exceeds_the_payment():
    allocs, rest = rules.allocate(700, [("a", 0), ("b", 300), ("c", 900)])
    assert allocs == [("b", 300), ("c", 400)] and rest == 0
    assert sum(c for _, c in allocs) + rest == 700


def test_balance_formula():
    assert rules.balance(500, 12000, 3000) == 9500
    assert rules.balance(0, 0, 100) == -100  # credit on account


@pytest.mark.parametrize("number,code", [
    ("MAM-W1-0042", "W1"), ("MAM-A9B-12345", "A9B"), ("MAM-W1-042", None),
    ("MAM-w1-0042", None), ("X-W1-0042", None), ("MAM-W1-0042x", None), (None, None),
])
def test_number_device_code(number, code):
    assert rules.number_device_code(number) == code


def test_line_total_is_exact_integer_cents():
    assert rules.line_total(10, 250) == 2500
    assert isinstance(rules.line_total(3, 777), int)


# ---- services with the database -------------------------------------------------------------


@pytest.mark.django_db
def test_at1_through_services():
    world = build_world()

    def invoice(n, total, day):
        return services.store_invoice(
            worker=world.worker, device=world.device,
            data={
                "id": f"00000000-0000-4000-8000-00000000000{n}", "number": f"MAM-W1-000{n}",
                "customer": world.customer, "issued_at": utc(day),
                "items": [{"id": f"10000000-0000-4000-8000-00000000000{n}",
                           "product": world.chapathi, "qty_packets": 1, "unit_price_cents": total}],
                "total_cents": total,
            },
        )

    i1, i2 = invoice(1, 12000, 3), invoice(2, 9000, 10)
    payment, allocs = services.store_payment(
        worker=world.worker, device=world.device,
        data={"id": "20000000-0000-4000-8000-000000000001", "customer": world.customer,
              "invoice": None, "amount_cents": 15000, "method": "cash", "paid_at": utc(12)},
    )
    i3 = invoice(3, 6000, 20)
    assert [(str(i), c) for i, c in allocs] == [(str(i1.pk), 12000), (str(i2.pk), 3000)]
    assert selectors.invoice_paid_cents(i1) == 12000  # invoice 1 paid
    assert i2.total_cents - selectors.invoice_paid_cents(i2) == 6000  # invoice 2 owes $60
    assert selectors.customer_balance(world.customer) == 12000  # closing $120.00
    open_now = [(str(i), c) for i, c in selectors.open_invoices(world.customer)]
    assert open_now == [(str(i2.pk), 6000), (str(i3.pk), 6000)]
    assert PaymentAllocation.objects.filter(payment=payment).count() == 2


@pytest.mark.django_db
def test_balance_is_never_stored():
    from apps.catalog.models import Customer

    assert not any("balance" in f.name and f.name != "opening_balance_cents"
                   for f in Customer._meta.get_fields())
