"""GET /api/v1/sync/customer-activity (Doc 2 s5, s6.4, I-6, I-8). Workers only."""
import pytest
from rest_framework.test import APIClient

from apps.catalog.models import CustomerType
from apps.catalog.services import create_customer, update_customer
from apps.sales import selectors, services
from tests.core_helpers import make_user, token_client
from tests.test_sales_helpers import build_world, invoice_payload, payment_payload, push_body

pytestmark = pytest.mark.django_db
URL = "/api/v1/sync/customer-activity"
PUSH = "/api/v1/sync/push"


@pytest.fixture
def world():
    return build_world()


def push(world, **kwargs):
    response = world.client.post(PUSH, push_body(world, **kwargs), format="json")
    assert response.status_code == 200
    return response.json()


def pull(world, cursor=None):
    params = {} if cursor is None else {"cursor": cursor}
    response = world.client.get(URL, params)
    assert response.status_code == 200, response.content
    return response.json()


def test_admin_403_anonymous_401(world):
    admin = make_user("boss", role="admin")
    assert token_client(admin).get(URL).status_code == 403
    assert APIClient().get(URL).status_code == 401


def test_empty_pull(world):
    assert pull(world) == {"cursor": 0, "customers": []}


def test_pull_returns_records_allocations_and_computed_balance(world):
    inv1 = invoice_payload(world, "MAM-W1-0001", 12000, day=3)
    inv2 = invoice_payload(world, "MAM-W1-0002", 9000, day=10)
    pay = payment_payload(15000, customer=world.customer, day=12, receipt_number="R1")
    push(world, invoices=[inv1, inv2], payments=[pay])
    body = pull(world)
    assert body["cursor"] == selectors.current_sales_cursor() > 0
    (entry,) = body["customers"]
    assert entry["customer_id"] == str(world.customer.id)
    assert entry["opening_balance_cents"] == 0
    assert entry["balance_cents"] == 6000  # 120 + 90 - 150
    assert {i["number"] for i in entry["invoices"]} == {"MAM-W1-0001", "MAM-W1-0002"}
    assert entry["invoices"][0]["items"][0]["product_name"] == world.chapathi.name
    assert [p["amount_cents"] for p in entry["payments"]] == [15000]
    assert entry["payments"][0]["receipt_number"] == "R1"
    assert sorted(a["amount_cents"] for a in entry["allocations"]) == [3000, 12000]
    assert {a["payment_id"] for a in entry["allocations"]} == {pay["id"]}


def test_pull_includes_opening_balance(world):
    admin = make_user("boss", role="admin")
    update_customer(world.customer, actor=admin, opening_balance_cents=2500)
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    (entry,) = pull(world)["customers"]
    assert entry["opening_balance_cents"] == 2500 and entry["balance_cents"] == 3500


def test_cursor_returns_only_changes_and_still_the_full_balance(world):
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    first = pull(world)
    push(world, payments=[payment_payload(300, customer=world.customer)])
    delta = pull(world, first["cursor"])
    (entry,) = delta["customers"]
    assert entry["invoices"] == [] and len(entry["payments"]) == 1
    assert entry["balance_cents"] == 700  # the whole balance, not just the delta
    assert delta["cursor"] > first["cursor"]
    again = pull(world, delta["cursor"])
    assert again["customers"] == [] and again["cursor"] == delta["cursor"]


def test_void_shows_up_as_a_change(world):
    inv = invoice_payload(world, "MAM-W1-0001", 1000)
    push(world, invoices=[inv])
    cursor = pull(world)["cursor"]
    admin = make_user("boss", role="admin")
    services.void_invoice(selectors.get_invoice(inv["id"]), actor=admin, reason="Mistake")
    (entry,) = pull(world, cursor)["customers"]
    assert entry["invoices"][0]["status"] == "void" and entry["balance_cents"] == 0


def test_cash_customers_and_walk_ins_are_not_included(world):
    cash = create_customer(
        name="Corner Shop", type=CustomerType.objects.get(name="Shop"), payment_mode="cash"
    )
    walk = invoice_payload(world, "MAM-W1-0001", 500, customer=None)
    shop = invoice_payload(world, "MAM-W1-0002", 500, customer=cash)
    push(world, invoices=[walk, shop], payments=[payment_payload(500, invoice_id=walk["id"])])
    assert pull(world)["customers"] == []


def test_cursor_safety_only_rows_at_or_below_the_counter(world, monkeypatch):
    """The counter is read first; rows above it wait for the next pull (Doc 2 s6.4)."""
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    ceiling = selectors.current_sales_cursor()
    # a write that commits after the counter was read
    real = selectors.current_sales_cursor

    def stale_counter():
        monkeypatch.setattr(selectors, "current_sales_cursor", real)
        value = real()
        push(world, invoices=[invoice_payload(world, "MAM-W1-0002", 2000)])
        return value

    monkeypatch.setattr(selectors, "current_sales_cursor", stale_counter)
    first = pull(world)
    assert first["cursor"] == ceiling
    (entry,) = first["customers"]
    assert [i["number"] for i in entry["invoices"]] == ["MAM-W1-0001"]
    assert entry["balance_cents"] == 1000  # computed at the same ceiling
    second = pull(world, first["cursor"])
    assert [i["number"] for i in second["customers"][0]["invoices"]] == ["MAM-W1-0002"]
    assert second["customers"][0]["balance_cents"] == 3000


@pytest.mark.parametrize("bad", ["abc", "-1", "1.5"])
def test_bad_cursor_is_a_400(world, bad):
    response = world.client.get(URL, {"cursor": bad})
    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"


def test_empty_cursor_counts_as_omitted(world):
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)])
    assert len(pull(world, cursor="")["customers"]) == 1


def test_no_cost_profit_or_expense_keys_anywhere(world):
    push(world, invoices=[invoice_payload(world, "MAM-W1-0001", 1000)],
         payments=[payment_payload(100, customer=world.customer)])

    def keys(node):
        if isinstance(node, dict):
            for k, v in node.items():
                yield k
                yield from keys(v)
        elif isinstance(node, list):
            for v in node:
                yield from keys(v)

    words = ("cost", "profit", "expense")
    bad = [k for k in keys(pull(world)) if any(w in k.lower() for w in words)]
    assert bad == []
