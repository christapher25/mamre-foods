"""GET /api/v1/sync/catalog: worker catalog pull, AT-4 and the cursor.

Doc 2 s5 (endpoint), s6.4 (cursor pull), s8 and I-8 (no cost keys); Doc 3 AT-4.
"""
from datetime import date

import pytest
from django.contrib.auth import get_user_model
from django.db import connection
from rest_framework.test import APIClient

from apps.accounts import services as accounts_services
from apps.catalog import services
from apps.catalog.models import CustomerType, Product, SyncCounter
from tests.core_helpers import make_user, token_client, worker_client

pytestmark = pytest.mark.django_db

User = get_user_model()
URL = "/api/v1/sync/catalog"
FORBIDDEN_FRAGMENTS = ("cost", "profit", "expense")
COST_OR_BALANCE_KEYS = {"packing_cost_cents", "yield_per_kg", "opening_balance_cents", "notes"}


def all_keys(node):
    """Every dict key at any depth of a JSON value."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield key
            yield from all_keys(value)
    elif isinstance(node, list):
        for item in node:
            yield from all_keys(item)


@pytest.fixture
def admin():
    return make_user(username="boss", role="admin")


@pytest.fixture
def populated(admin):
    """A catalog with every kind of row, including fields that must stay hidden."""
    restaurant = CustomerType.objects.get(name="Restaurant")
    fresh = Product.objects.get(code="FRESH")
    customer = services.create_customer(
        name="Spice Garden", type=restaurant, phone="555-0100", address="1 Main St",
        payment_mode="credit", opening_balance_cents=12345, notes="Pays late; owes us a favour",
        actor=admin,
    )
    default = services.create_price_default(
        product=fresh, customer_type=restaurant, unit_price_cents=250,
        effective_from=date(2026, 1, 1), actor=admin, today=date(2026, 10, 2),
    )
    override = services.create_price_override(
        customer=customer, product=fresh, unit_price_cents=220,
        effective_from=date(2026, 1, 1), note="Special", actor=admin, today=date(2026, 10, 2),
    )
    accounts_services.set_setting("address", "5 Dough Lane")
    return {"customer": customer, "default": default, "override": override, "fresh": fresh}


def pull(cursor=None, client=None):
    user = User.objects.filter(username="w1user").first() or make_user()
    client = client or worker_client(user)
    params = {} if cursor is None else {"cursor": cursor}
    return client.get(URL, params)


# --- access ---------------------------------------------------------------------------------------


def test_requires_authentication():
    response = APIClient().get(URL)
    assert response.status_code == 401
    assert set(response.json()) == {"code", "message", "details"}


def test_admin_token_is_forbidden(admin):
    response = token_client(admin).get(URL)
    assert response.status_code == 403
    assert response.json()["code"] == "permission_denied"


def test_deactivated_worker_is_refused():
    user = make_user()
    client = worker_client(user)
    user.is_active = False
    user.save()
    assert client.get(URL).status_code == 401


@pytest.mark.parametrize("bad", ["abc", "-1", "1.5"])
def test_bad_cursor_is_a_validation_error(bad):
    response = pull(bad)
    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"


def test_post_is_not_allowed():
    assert worker_client().post(URL, {}, format="json").status_code == 405


# --- shape and AT-4 -------------------------------------------------------------------------------


def test_full_pull_shape(populated):
    body = pull().json()
    assert set(body) == {
        "cursor", "products", "customer_types", "customers",
        "price_defaults", "price_overrides", "settings",
    }
    assert len(body["products"]) == 2
    assert {t["name"] for t in body["customer_types"]} == {"Restaurant", "Shop", "Retail"}
    assert len(body["customers"]) == 1
    assert len(body["price_defaults"]) == 1
    assert len(body["price_overrides"]) == 1


def test_at4_no_cost_profit_or_expense_key_anywhere(populated):
    body = pull().json()
    keys = list(all_keys(body))
    assert keys  # the scan is not vacuous
    bad = [k for k in keys if any(f in k.lower() for f in FORBIDDEN_FRAGMENTS)]
    assert bad == []


def test_at4_hidden_fields_are_absent(populated):
    body = pull().json()
    assert COST_OR_BALANCE_KEYS.isdisjoint(all_keys(body))
    assert "balance" not in {k.lower() for k in all_keys(body)}
    # And the values do not leak under another name.
    text = str(body)
    assert "Pays late" not in text
    assert "12345" not in text


def test_product_whitelist():
    product = next(p for p in pull().json()["products"] if p["code"] == "FRESH")
    assert set(product) == {"id", "code", "name", "units_per_packet", "is_active"}
    assert product["units_per_packet"] == 12
    assert product["name"] == "Mamre Fresh Chapathi"


def test_customer_whitelist(populated):
    customer = pull().json()["customers"][0]
    assert set(customer) == {
        "id", "name", "type_id", "phone", "address", "payment_mode", "is_active",
    }
    assert customer["type_id"] == str(populated["customer"].type_id)
    assert customer["payment_mode"] == "credit"
    assert customer["name"] == "Spice Garden"
    assert customer["phone"] == "555-0100"
    assert customer["address"] == "1 Main St"
    assert customer["is_active"] is True


def test_price_rows_are_integer_cents_with_iso_dates(populated):
    body = pull().json()
    default = body["price_defaults"][0]
    override = body["price_overrides"][0]
    assert set(default) == {
        "id", "product_id", "customer_type_id", "unit_price_cents", "effective_from",
    }
    assert set(override) == {
        "id", "customer_id", "product_id", "unit_price_cents", "effective_from", "is_active",
    }
    assert default["unit_price_cents"] == 250 and type(default["unit_price_cents"]) is int
    assert override["unit_price_cents"] == 220
    assert default["effective_from"] == "2026-01-01"
    assert override["customer_id"] == str(populated["customer"].pk)  # note stays hidden


def test_settings_are_header_keys_only(populated):
    accounts_services.set_setting("internal_flag", "do not send")
    settings = {s["key"]: s["value"] for s in pull().json()["settings"]}
    assert settings == {"business_name": "Mamre Foods", "address": "5 Dough Lane"}


def test_fresh_catalog_has_no_prices():
    body = pull().json()
    assert body["price_defaults"] == [] and body["price_overrides"] == []
    assert body["customers"] == []


# --- cursor ---------------------------------------------------------------------------------------


def test_cursor_zero_and_missing_return_everything(populated):
    assert pull().json() == pull(0).json()


def test_cursor_returns_only_changes(populated, admin):
    first = pull().json()
    cursor = first["cursor"]
    assert cursor > 0

    nothing = pull(cursor).json()
    assert nothing["cursor"] == cursor
    for key in ("products", "customer_types", "customers", "price_defaults",
                "price_overrides", "settings"):
        assert nothing[key] == []

    services.update_customer(populated["customer"], phone="555-0199")
    changed = pull(cursor).json()
    assert [c["phone"] for c in changed["customers"]] == ["555-0199"]
    assert changed["products"] == [] and changed["price_defaults"] == []
    assert changed["cursor"] > cursor

    again = pull(changed["cursor"]).json()
    assert again["customers"] == [] and again["cursor"] == changed["cursor"]


def test_cursor_covers_every_row_kind(populated, admin):
    cursor = pull().json()["cursor"]
    services.update_product(populated["fresh"], name="Fresh Chapathi")
    # The fixture prices are already in effect, so correct future-dated rows in place instead.
    today = date(2026, 10, 2)
    future_default = services.create_price_default(
        product=populated["fresh"], customer_type=populated["customer"].type,
        unit_price_cents=255, effective_from=date(2026, 12, 1), actor=admin, today=today,
    )
    future_override = services.create_price_override(
        customer=populated["customer"], product=populated["fresh"], unit_price_cents=205,
        effective_from=date(2026, 12, 1), actor=admin, today=today,
    )
    services.update_price_default(future_default, actor=admin, today=today, unit_price_cents=260)
    services.update_price_override(future_override, actor=admin, today=today, unit_price_cents=210)
    ctype = services.create_customer_type(name="Caterer")
    accounts_services.set_setting("phone", "555-0123")
    body = pull(cursor).json()
    assert [p["name"] for p in body["products"]] == ["Fresh Chapathi"]
    assert [p["unit_price_cents"] for p in body["price_defaults"]] == [260]
    assert [p["unit_price_cents"] for p in body["price_overrides"]] == [210]
    assert [t["id"] for t in body["customer_types"]] == [str(ctype.pk)]
    assert [s["key"] for s in body["settings"]] == ["phone"]


def test_full_pull_cursor_equals_the_highest_version_issued(populated):
    assert pull().json()["cursor"] == SyncCounter.objects.get().value


def test_inactive_rows_are_sent_with_is_active_false(populated, admin):
    cursor = pull().json()["cursor"]
    services.update_customer(populated["customer"], is_active=False)
    services.update_product(populated["fresh"], is_active=False)
    services.update_price_override(
        populated["override"], actor=admin, today=date(2026, 10, 2), is_active=False
    )
    ctype = CustomerType.objects.get(name="Shop")
    services.update_customer_type(ctype, is_active=False)

    body = pull(cursor).json()
    assert [c["is_active"] for c in body["customers"]] == [False]
    assert [p["is_active"] for p in body["products"]] == [False]
    assert [o["is_active"] for o in body["price_overrides"]] == [False]
    assert [(t["name"], t["is_active"]) for t in body["customer_types"]] == [("Shop", False)]


def _force_version(table, code_column, code, version):
    """Set a row's sync_version with raw SQL, simulating a write the pull must not see yet."""
    with connection.cursor() as cursor:
        cursor.execute(
            f"UPDATE {table} SET sync_version = %s WHERE {code_column} = %s", [version, code]
        )


def test_rows_above_the_counter_are_not_returned_and_cursor_stays_at_the_counter(populated):
    counter = SyncCounter.objects.get().value
    cursor = pull().json()["cursor"]
    assert cursor == counter
    # A row whose version is above the counter was not published by a committed write yet.
    _force_version("catalog_product", "code", "CHAPATHI", counter + 5)
    body = pull(cursor).json()
    assert body["products"] == []
    assert body["cursor"] == counter
    # A full pull must not return it either.
    assert [p["code"] for p in pull(0).json()["products"]] == ["FRESH"]
    assert pull(0).json()["cursor"] == counter


def test_the_row_arrives_once_the_counter_catches_up(populated):
    counter = SyncCounter.objects.get().value
    _force_version("catalog_product", "code", "CHAPATHI", counter + 1)
    assert pull(counter).json()["products"] == []
    SyncCounter.objects.filter(pk=1).update(value=counter + 1)
    body = pull(counter).json()
    assert [p["code"] for p in body["products"]] == ["CHAPATHI"]
    assert body["cursor"] == counter + 1


def test_unchanged_cursor_returns_nothing_and_the_same_cursor(populated):
    counter = SyncCounter.objects.get().value
    body = pull(counter).json()
    assert body["cursor"] == counter
    for key in ("products", "customer_types", "customers", "price_defaults",
                "price_overrides", "settings"):
        assert body[key] == []


def test_setting_rows_above_the_counter_are_not_returned(populated):
    counter = SyncCounter.objects.get().value
    _force_version("accounts_appsetting", "key", "address", counter + 3)
    assert "address" not in {s["key"] for s in pull(0).json()["settings"]}


def test_a_cursor_from_the_future_gets_the_server_counter_back(populated):
    # A device ahead of the server (for example after a restore) is reset to the counter.
    counter = SyncCounter.objects.get().value
    body = pull(10_000_000).json()
    assert body["customers"] == [] and body["products"] == []
    assert body["cursor"] == counter


def test_workers_cannot_change_prices_through_the_api(populated):
    client = worker_client()
    for method in (client.post, client.put, client.patch, client.delete):
        assert method(URL, {}, format="json").status_code == 405
