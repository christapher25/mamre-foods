"""Catalog models, seed data and the sync_version cursor.

Doc 2 s4.1 (conventions, sync_version), s4.2 (tables); Doc 1 s3, s4.1; DECISIONS.md P1.
"""
from decimal import Decimal

import pytest
from django.db import IntegrityError, transaction

from apps.accounts import services as accounts_services
from apps.accounts.models import AppSetting
from apps.catalog import services
from apps.catalog.models import (
    Customer,
    CustomerType,
    PriceDefault,
    PriceOverride,
    Product,
    SyncCounter,
)

pytestmark = pytest.mark.django_db


# --- Seed (Doc 1 s3, s4.1; P-4 pending, P-6 pending) -----------------------------------


def test_seed_customer_types():
    assert set(CustomerType.objects.values_list("name", flat=True)) == {
        "Restaurant",
        "Shop",
        "Retail",
    }
    assert CustomerType.objects.filter(is_active=False).count() == 0


def test_seed_products():
    products = {p.code: p for p in Product.objects.all()}
    assert set(products) == {"FRESH", "CHAPATHI"}
    assert products["FRESH"].name == "Mamre Fresh Chapathi"
    assert products["CHAPATHI"].name == "Mamre Chapathi"
    for product in products.values():
        assert product.units_per_packet == 12
        assert product.packing_cost_cents == 15
        assert product.yield_per_kg == Decimal("32")
        assert product.is_active is True


def test_seed_has_no_prices_or_customers():
    # P-4 is pending: never invent a price. P-8: customers start empty.
    assert PriceDefault.objects.count() == 0
    assert PriceOverride.objects.count() == 0
    assert Customer.objects.count() == 0


def test_seed_settings_only_business_name():
    # P-6 is pending: no address, phone or footer.
    assert dict(AppSetting.objects.values_list("key", "value")) == {
        "business_name": "Mamre Foods"
    }


def test_seed_rows_have_unique_positive_sync_versions():
    versions = [
        *CustomerType.objects.values_list("sync_version", flat=True),
        *Product.objects.values_list("sync_version", flat=True),
        *AppSetting.objects.values_list("sync_version", flat=True),
    ]
    assert all(v > 0 for v in versions)
    assert len(versions) == len(set(versions))
    assert SyncCounter.objects.get().value == max(versions)


def test_uuid_primary_keys():
    assert len(str(Product.objects.first().pk)) == 36
    assert len(str(CustomerType.objects.first().pk)) == 36


# --- sync_version: every change bumps it, only through services (owner answer 7) ------------


def _current():
    return SyncCounter.objects.get().value


def _customer(**overrides):
    fields = {
        "name": "Spice Garden",
        "type": CustomerType.objects.get(name="Restaurant"),
        "payment_mode": "credit",
    }
    fields.update(overrides)
    return services.create_customer(**fields)


def test_every_catalog_change_bumps_sync_version():
    steps = []
    start = _current()

    product = services.create_product(
        code="X1", name="Extra", units_per_packet=12, packing_cost_cents=10, yield_per_kg="30"
    )
    steps.append(product.sync_version)
    steps.append(services.update_product(product, name="Extra 2").sync_version)

    ctype = services.create_customer_type(name="Caterer")
    steps.append(ctype.sync_version)
    steps.append(services.update_customer_type(ctype, is_active=False).sync_version)

    customer = _customer()
    steps.append(customer.sync_version)
    steps.append(services.update_customer(customer, phone="555-0100").sync_version)

    steps.append(accounts_services.set_setting("phone", "555-0199").sync_version)
    steps.append(accounts_services.set_setting("phone", "555-0123").sync_version)

    assert steps == list(range(start + 1, start + 1 + len(steps)))
    assert _current() == steps[-1]


def test_saving_a_synced_row_without_a_bump_is_refused():
    product = Product.objects.get(code="FRESH")
    product.name = "Sneaky edit"
    with pytest.raises(RuntimeError):
        product.save()


def test_new_synced_row_without_a_version_is_refused():
    with pytest.raises(RuntimeError):
        CustomerType(name="Direct").save()


def test_update_rejects_unknown_fields():
    product = Product.objects.get(code="FRESH")
    with pytest.raises(ValueError):
        services.update_product(product, sync_version=1)


def test_failed_write_does_not_burn_a_version():
    before = _current()
    with pytest.raises(IntegrityError), transaction.atomic():
        services.create_customer_type(name="Restaurant")  # duplicate name
    assert _current() == before


# --- Field rules (Doc 2 s4.2) ----------------------------------------------------------------


def test_customer_defaults_and_optional_fields():
    customer = _customer()
    assert customer.opening_balance_cents == 0
    assert customer.is_active is True
    assert customer.phone == "" and customer.address == "" and customer.notes == ""
    assert isinstance(customer.opening_balance_cents, int)


def test_customer_rejects_unknown_payment_mode():
    with pytest.raises(ValueError):
        _customer(payment_mode="barter")


def test_product_code_is_unique():
    with pytest.raises(IntegrityError), transaction.atomic():
        services.create_product(
            code="FRESH", name="Dup", units_per_packet=12, packing_cost_cents=15, yield_per_kg="32"
        )


def test_product_rejects_non_positive_units_or_yield():
    for bad in ({"units_per_packet": 0}, {"yield_per_kg": "0"}, {"packing_cost_cents": -1}):
        fields = {
            "code": "BAD",
            "name": "Bad",
            "units_per_packet": 12,
            "packing_cost_cents": 15,
            "yield_per_kg": "32",
        } | bad
        with pytest.raises(IntegrityError), transaction.atomic():
            services.create_product(**fields)
