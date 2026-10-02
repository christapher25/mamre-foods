"""Price resolution (AT-2), effective_from history, price rules and price audit.

Doc 1 s4.2 (resolution order), s4.3 (price rules); Doc 2 s4.2, s8 (AuditLog); Doc 3 AT-2.
"""
from datetime import date, datetime

import pytest
from django.db import IntegrityError, transaction

from apps.accounts.models import AuditLog
from apps.catalog import services
from apps.catalog.models import CustomerType, PriceDefault, PriceOverride, Product
from apps.catalog.pricing import NoPriceError, pick_price, resolve_price
from tests.core_helpers import make_user

pytestmark = pytest.mark.django_db

D = date
TODAY = D(2026, 10, 2)  # fixed so tests do not depend on the real clock
FUTURE = D(2026, 12, 1)


@pytest.fixture
def admin():
    return make_user(username="boss", role="admin")


@pytest.fixture
def product():
    return Product.objects.get(code="FRESH")


@pytest.fixture
def other_product():
    return Product.objects.get(code="CHAPATHI")


@pytest.fixture
def restaurant():
    return CustomerType.objects.get(name="Restaurant")


@pytest.fixture
def customer(restaurant):
    return services.create_customer(name="Spice Garden", type=restaurant, payment_mode="credit")


def set_default(admin, restaurant, product, cents, frm):
    return services.create_price_default(
        product=product, customer_type=restaurant, unit_price_cents=cents,
        effective_from=frm, actor=admin, today=TODAY,
    )


def set_override(admin, customer, product, cents, frm, **extra):
    return services.create_price_override(
        customer=customer, product=product, unit_price_cents=cents,
        effective_from=frm, actor=admin, today=TODAY, **extra,
    )


# --- pure function -----------------------------------------------------------------------------


def test_pick_price_latest_effective_not_after_at_wins():
    rows = [(D(2026, 1, 1), 250), (D(2026, 6, 1), 280), (D(2026, 12, 1), 300)]
    assert pick_price([], rows, D(2026, 5, 31)) == 250
    assert pick_price([], rows, D(2026, 6, 1)) == 280  # effective_from == at counts
    assert pick_price([], rows, D(2026, 11, 30)) == 280
    assert pick_price([], rows, D(2027, 1, 1)) == 300


def test_pick_price_order_of_rows_does_not_matter():
    rows = [(D(2026, 6, 1), 280), (D(2026, 1, 1), 250)]
    assert pick_price([], rows, D(2026, 7, 1)) == 280


def test_pick_price_override_beats_default_even_if_default_is_newer():
    assert pick_price([(D(2026, 1, 1), 200)], [(D(2026, 9, 1), 250)], D(2026, 10, 1)) == 200


def test_pick_price_future_override_does_not_beat_current_default():
    assert pick_price([(D(2026, 12, 1), 200)], [(D(2026, 1, 1), 250)], D(2026, 10, 1)) == 250


def test_pick_price_nothing_effective_raises_never_zero():
    with pytest.raises(NoPriceError):
        pick_price([], [], D(2026, 10, 1))
    with pytest.raises(NoPriceError):
        pick_price([], [(D(2026, 11, 1), 250)], D(2026, 10, 1))


# --- resolve_price against the database (AT-2) ---------------------------------------------------


def test_at2_override_beats_default(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    set_override(admin, customer, product, 220, D(2026, 1, 1))
    assert resolve_price(customer, product, D(2026, 10, 2)) == 220


def test_at2_default_used_without_override(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    assert resolve_price(customer, product, D(2026, 10, 2)) == 250


def test_at2_missing_price_blocks(customer, product):
    with pytest.raises(NoPriceError):
        resolve_price(customer, product, D(2026, 10, 2))


def test_override_is_per_customer_and_per_product(
    admin, customer, restaurant, product, other_product
):
    shop_customer = services.create_customer(
        name="Corner", type=CustomerType.objects.get(name="Shop"), payment_mode="cash"
    )
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    set_override(admin, customer, product, 220, D(2026, 1, 1))
    with pytest.raises(NoPriceError):
        resolve_price(customer, other_product, D(2026, 10, 2))  # no price for that product
    with pytest.raises(NoPriceError):
        resolve_price(shop_customer, product, D(2026, 10, 2))  # other type, no default


def test_inactive_override_is_ignored(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    override = set_override(admin, customer, product, 220, D(2026, 1, 1))
    services.update_price_override(override, actor=admin, today=TODAY, is_active=False)
    assert resolve_price(customer, product, D(2026, 10, 2)) == 250


def test_effective_from_history_for_defaults(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    set_default(admin, restaurant, product, 270, D(2026, 9, 1))
    assert resolve_price(customer, product, D(2026, 8, 31)) == 250
    assert resolve_price(customer, product, D(2026, 9, 1)) == 270
    assert PriceDefault.objects.count() == 2  # history kept


def test_effective_from_history_for_overrides(admin, customer, product):
    set_override(admin, customer, product, 200, D(2026, 1, 1))
    set_override(admin, customer, product, 190, D(2026, 9, 15))
    assert resolve_price(customer, product, D(2026, 9, 14)) == 200
    assert resolve_price(customer, product, D(2026, 9, 15)) == 190
    assert PriceOverride.objects.count() == 2


def test_price_before_first_effective_date_is_no_price(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 11, 1))
    with pytest.raises(NoPriceError):
        resolve_price(customer, product, D(2026, 10, 2))


def test_resolve_price_returns_int_cents(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    price = resolve_price(customer, product, D(2026, 10, 2))
    assert type(price) is int


# --- price rules (Doc 1 s4.3, owner answer 3) ----------------------------------------------------


@pytest.mark.parametrize("bad", [0, -1, -250, 2.5, "250", True, None])
def test_price_must_be_a_positive_integer(admin, restaurant, product, bad):
    with pytest.raises(ValueError):
        set_default(admin, restaurant, product, bad, D(2026, 1, 1))


def test_effective_from_must_be_a_date(admin, restaurant, product):
    with pytest.raises(ValueError):
        set_default(admin, restaurant, product, 250, datetime(2026, 1, 1, 12, 0))
    with pytest.raises(ValueError):
        set_default(admin, restaurant, product, 250, "2026-01-01")


def test_database_rejects_non_positive_price_even_if_service_is_bypassed(restaurant, product):
    row = PriceDefault(
        product=product, customer_type=restaurant, unit_price_cents=0,
        effective_from=D(2026, 1, 1), sync_version=1,
    )
    with pytest.raises(IntegrityError), transaction.atomic():
        row.save()


def test_default_unique_per_type_product_and_date(admin, restaurant, product):
    set_default(admin, restaurant, product, 250, FUTURE)
    with pytest.raises(IntegrityError), transaction.atomic():
        set_default(admin, restaurant, product, 260, FUTURE)


def test_override_unique_per_customer_product_and_date(admin, customer, product):
    set_override(admin, customer, product, 200, FUTURE)
    with pytest.raises(IntegrityError), transaction.atomic():
        set_override(admin, customer, product, 210, FUTURE)


def test_price_default_has_no_deactivate_or_delete_service():
    assert not hasattr(services, "delete_price_default")
    assert "is_active" not in [f.name for f in PriceDefault._meta.get_fields()]


# --- audit (Doc 2 s4.2, s8) ----------------------------------------------------------------------


def test_creating_a_default_writes_audit(admin, restaurant, product):
    price = set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    entry = AuditLog.objects.get(entity="PriceDefault")
    assert entry.action == "price_default.create"
    assert entry.user == admin
    assert entry.entity_id == str(price.pk)
    assert entry.before_json is None
    assert entry.after_json["unit_price_cents"] == 250
    assert entry.after_json["effective_from"] == "2026-01-01"


def test_changing_a_default_writes_before_and_after(admin, restaurant, product):
    price = set_default(admin, restaurant, product, 250, FUTURE)
    services.update_price_default(price, actor=admin, today=TODAY, unit_price_cents=260)
    entry = AuditLog.objects.get(action="price_default.update")
    assert entry.action == "price_default.update"
    assert entry.before_json["unit_price_cents"] == 250
    assert entry.after_json["unit_price_cents"] == 260


def test_override_create_and_change_write_audit(admin, customer, product):
    override = set_override(admin, customer, product, 200, FUTURE, note="Loyal")
    services.update_price_override(
        override, actor=admin, today=TODAY, unit_price_cents=190, is_active=False
    )
    entries = [
        AuditLog.objects.get(action="price_override.create"),
        AuditLog.objects.get(action="price_override.update"),
    ]
    assert entries[0].after_json["note"] == "Loyal"
    assert entries[1].before_json["unit_price_cents"] == 200
    assert entries[1].before_json["is_active"] is True
    assert entries[1].after_json["unit_price_cents"] == 190
    assert entries[1].after_json["is_active"] is False


def test_failed_price_write_leaves_no_audit_row(admin, restaurant, product):
    set_default(admin, restaurant, product, 250, FUTURE)
    before = AuditLog.objects.count()
    with pytest.raises(IntegrityError), transaction.atomic():
        set_default(admin, restaurant, product, 260, FUTURE)
    assert AuditLog.objects.count() == before


def test_price_changes_bump_sync_version(admin, customer, restaurant, product):
    price = set_default(admin, restaurant, product, 250, FUTURE)
    first = price.sync_version
    services.update_price_default(price, actor=admin, today=TODAY, unit_price_cents=260)
    assert price.sync_version > first
    override = set_override(admin, customer, product, 200, D(2026, 1, 1))
    assert override.sync_version > price.sync_version


def test_update_rejects_unknown_price_fields(admin, restaurant, product):
    price = set_default(admin, restaurant, product, 250, FUTURE)
    with pytest.raises(ValueError):
        services.update_price_default(
            price, actor=admin, today=TODAY, effective_from=D(2027, 1, 1)
        )
