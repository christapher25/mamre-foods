"""Price history immutability and admin-only price writes.

Doc 1 s4.3 (history kept), s2 (Admin sets prices); Doc 2 s8 (AuditLog); review items 4 and 5.
"""
from datetime import date

import pytest

from apps.accounts.exceptions import AdminRequired
from apps.accounts.models import AuditLog
from apps.catalog import services
from apps.catalog.exceptions import PriceHistoryError
from apps.catalog.models import CustomerType, PriceDefault, PriceOverride, Product
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


def set_default(admin, ctype, product, cents, frm):
    return services.create_price_default(
        product=product, customer_type=ctype, unit_price_cents=cents,
        effective_from=frm, actor=admin, today=TODAY,
    )


def set_override(admin, customer, product, cents, frm, **extra):
    return services.create_price_override(
        customer=customer, product=product, unit_price_cents=cents,
        effective_from=frm, actor=admin, today=TODAY, **extra,
    )


# --- price history is immutable once in effect (review item 4) --------------------------------


@pytest.mark.parametrize("frm", [D(2026, 1, 1), TODAY], ids=["past", "today"])
def test_default_in_effect_cannot_have_its_price_changed(admin, restaurant, product, frm):
    price = set_default(admin, restaurant, product, 250, frm)
    with pytest.raises(PriceHistoryError) as error:
        services.update_price_default(price, actor=admin, today=TODAY, unit_price_cents=260)
    assert "later effective_from" in str(error.value)
    price.refresh_from_db()
    assert price.unit_price_cents == 250
    assert AuditLog.objects.filter(action="price_default.update").count() == 0


@pytest.mark.parametrize("frm", [D(2026, 1, 1), TODAY], ids=["past", "today"])
def test_override_in_effect_cannot_have_its_price_changed(admin, customer, product, frm):
    override = set_override(admin, customer, product, 200, frm)
    with pytest.raises(PriceHistoryError):
        services.update_price_override(override, actor=admin, today=TODAY, unit_price_cents=190)
    # A price change mixed with other fields is refused as a whole.
    with pytest.raises(PriceHistoryError):
        services.update_price_override(
            override, actor=admin, today=TODAY, unit_price_cents=190, note="changed"
        )
    override.refresh_from_db()
    assert (override.unit_price_cents, override.note) == (200, "")


def test_override_in_effect_can_still_be_deactivated_or_annotated(admin, customer, product):
    override = set_override(admin, customer, product, 200, D(2026, 1, 1))
    services.update_price_override(
        override, actor=admin, today=TODAY, is_active=False, note="No longer agreed"
    )
    override.refresh_from_db()
    assert override.is_active is False and override.note == "No longer agreed"
    assert override.unit_price_cents == 200
    assert AuditLog.objects.filter(action="price_override.update").count() == 1


def test_future_rows_can_be_corrected_in_place_and_keep_their_audit(
    admin, customer, restaurant, product
):
    default = set_default(admin, restaurant, product, 250, FUTURE)
    override = set_override(admin, customer, product, 200, FUTURE)
    services.update_price_default(default, actor=admin, today=TODAY, unit_price_cents=255)
    services.update_price_override(override, actor=admin, today=TODAY, unit_price_cents=195)
    default.refresh_from_db()
    override.refresh_from_db()
    assert (default.unit_price_cents, override.unit_price_cents) == (255, 195)
    assert AuditLog.objects.filter(action__endswith=".update").count() == 2
    assert AuditLog.objects.filter(action__endswith=".create").count() == 2


def test_a_price_change_is_a_new_row_with_a_later_date(admin, customer, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    set_default(admin, restaurant, product, 270, D(2026, 9, 1))
    set_override(admin, customer, product, 200, D(2026, 1, 1))
    set_override(admin, customer, product, 190, D(2026, 9, 1))
    assert PriceDefault.objects.count() == 2 and PriceOverride.objects.count() == 2


@pytest.mark.parametrize("frm", [D(2026, 1, 1), D(2025, 12, 31)], ids=["same", "earlier"])
def test_new_default_must_be_later_than_the_current_row(admin, restaurant, product, frm):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    with pytest.raises(PriceHistoryError) as error:
        set_default(admin, restaurant, product, 260, frm)
    assert "2026-01-01" in str(error.value)
    assert PriceDefault.objects.count() == 1


@pytest.mark.parametrize("frm", [D(2026, 1, 1), D(2025, 12, 31)], ids=["same", "earlier"])
def test_new_override_must_be_later_than_the_current_row(admin, customer, product, frm):
    set_override(admin, customer, product, 200, D(2026, 1, 1))
    with pytest.raises(PriceHistoryError):
        set_override(admin, customer, product, 190, frm)
    assert PriceOverride.objects.count() == 1


def test_current_row_is_the_one_in_effect_not_a_future_row(admin, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2026, 1, 1))
    set_default(admin, restaurant, product, 300, FUTURE)
    # Later than the row in effect (2026-01-01) but earlier than the future row: allowed.
    set_default(admin, restaurant, product, 270, D(2026, 11, 1))
    assert PriceDefault.objects.count() == 3


def test_the_first_price_may_have_any_date(admin, restaurant, product):
    set_default(admin, restaurant, product, 250, D(2020, 1, 1))
    assert PriceDefault.objects.count() == 1


def test_history_rule_is_per_product_type_and_customer(
    admin, customer, restaurant, product, other_product
):
    set_default(admin, restaurant, product, 250, D(2026, 6, 1))
    set_default(admin, restaurant, other_product, 280, D(2026, 1, 1))  # other product
    set_default(admin, CustomerType.objects.get(name="Shop"), product, 240, D(2026, 1, 1))
    set_override(admin, customer, product, 200, D(2026, 6, 1))
    set_override(admin, customer, other_product, 210, D(2026, 1, 1))
    assert PriceDefault.objects.count() == 3 and PriceOverride.objects.count() == 2


# --- price writes need an active admin (review item 5) -----------------------------------------


@pytest.fixture(params=["none", "worker", "inactive_admin"])
def bad_actor(request):
    if request.param == "none":
        return None
    if request.param == "worker":
        return make_user(username="wk", role="worker")
    return make_user(username="gone", role="admin", is_active=False)


def test_create_default_requires_an_active_admin(bad_actor, restaurant, product):
    with pytest.raises(AdminRequired):
        services.create_price_default(
            product=product, customer_type=restaurant, unit_price_cents=250,
            effective_from=D(2026, 1, 1), actor=bad_actor, today=TODAY,
        )
    assert PriceDefault.objects.count() == 0 and AuditLog.objects.count() == 0


def test_create_override_requires_an_active_admin(bad_actor, customer, product):
    with pytest.raises(AdminRequired):
        services.create_price_override(
            customer=customer, product=product, unit_price_cents=200,
            effective_from=D(2026, 1, 1), actor=bad_actor, today=TODAY,
        )
    assert PriceOverride.objects.count() == 0 and AuditLog.objects.count() == 0


def test_update_default_requires_an_active_admin(admin, bad_actor, restaurant, product):
    price = set_default(admin, restaurant, product, 250, FUTURE)
    audit_rows = AuditLog.objects.count()
    with pytest.raises(AdminRequired):
        services.update_price_default(price, actor=bad_actor, today=TODAY, unit_price_cents=260)
    price.refresh_from_db()
    assert price.unit_price_cents == 250 and AuditLog.objects.count() == audit_rows


def test_update_override_requires_an_active_admin(admin, bad_actor, customer, product):
    override = set_override(admin, customer, product, 200, FUTURE)
    audit_rows = AuditLog.objects.count()
    with pytest.raises(AdminRequired):
        services.update_price_override(override, actor=bad_actor, today=TODAY, is_active=False)
    override.refresh_from_db()
    assert override.is_active is True and AuditLog.objects.count() == audit_rows


def test_admin_required_is_a_permission_error():
    assert issubclass(AdminRequired, PermissionError)


def test_the_actor_is_re_read_so_a_stale_object_keeps_no_old_role(restaurant, product):
    stale = make_user(username="stale", role="admin")
    type(stale).objects.filter(pk=stale.pk).update(role="worker")
    with pytest.raises(AdminRequired):
        set_default(stale, restaurant, product, 250, D(2026, 1, 1))
