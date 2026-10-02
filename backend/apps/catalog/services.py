"""All writes for products, customer types, customers, prices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
Every write bumps sync_version (Doc 2 s4.1) inside one transaction.
"""
from django.db import transaction

from .models import Customer, CustomerType, PaymentMode, Product, SyncCounter

PRODUCT_FIELDS = {
    "code", "name", "units_per_packet", "packing_cost_cents", "yield_per_kg", "is_active",
}
CUSTOMER_TYPE_FIELDS = {"name", "is_active"}
CUSTOMER_FIELDS = {
    "name", "type", "phone", "address", "payment_mode", "opening_balance_cents",
    "notes", "is_active",
}


def next_sync_version():
    """The next monotonic cursor value. Call inside the same transaction as the write."""
    with transaction.atomic():
        counter, _ = SyncCounter.objects.select_for_update().get_or_create(pk=1)
        counter.value += 1
        counter.save(update_fields=["value"])
        return counter.value


def _check_fields(fields, allowed):
    unknown = set(fields) - allowed
    if unknown:
        raise ValueError(f"Fields not allowed: {sorted(unknown)}")


def _check_payment_mode(fields):
    if "payment_mode" in fields and fields["payment_mode"] not in PaymentMode.values:
        raise ValueError(f"Unknown payment_mode: {fields['payment_mode']!r}")


def _create(model, allowed, fields):
    _check_fields(fields, allowed)
    _check_payment_mode(fields)
    with transaction.atomic():
        obj = model(**fields)
        obj.sync_version = next_sync_version()
        obj.save(force_insert=True)
    return obj


def _update(obj, allowed, fields):
    _check_fields(fields, allowed)
    _check_payment_mode(fields)
    with transaction.atomic():
        for name, value in fields.items():
            setattr(obj, name, value)
        obj.sync_version = next_sync_version()
        obj.save()
    return obj


def create_product(**fields):
    return _create(Product, PRODUCT_FIELDS, fields)


def update_product(product, **fields):
    return _update(product, PRODUCT_FIELDS, fields)


def create_customer_type(**fields):
    return _create(CustomerType, CUSTOMER_TYPE_FIELDS, fields)


def update_customer_type(customer_type, **fields):
    return _update(customer_type, CUSTOMER_TYPE_FIELDS, fields)


def create_customer(**fields):
    return _create(Customer, CUSTOMER_FIELDS, fields)


def update_customer(customer, **fields):
    return _update(customer, CUSTOMER_FIELDS, fields)
