"""All writes for products, customer types, customers, prices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
Every write bumps sync_version (Doc 2 s4.1) inside one transaction.
"""
from datetime import date, datetime

from django.db import transaction
from django.utils import timezone

from apps.accounts.services import record_audit, require_admin

from . import selectors
from .exceptions import PriceHistoryError
from .models import (
    Customer,
    CustomerType,
    PaymentMode,
    PriceDefault,
    PriceOverride,
    Product,
    SyncCounter,
)

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


def _check_opening_balance(fields):
    if "opening_balance_cents" in fields:
        cents = fields["opening_balance_cents"]
        if type(cents) is not int:
            raise ValueError("opening_balance_cents must be an integer number of cents")


def create_customer(**fields):
    _check_opening_balance(fields)
    return _create(Customer, CUSTOMER_FIELDS, fields)


def update_customer(customer, *, actor=None, **fields):
    """Doc 2 s8: a balance correction needs an active admin and is audited (before/after).
    Changing any other field needs no actor."""
    _check_fields(fields, CUSTOMER_FIELDS)
    _check_opening_balance(fields)
    before = customer.opening_balance_cents
    changing = "opening_balance_cents" in fields and fields["opening_balance_cents"] != before
    if changing:
        require_admin(actor)
    with transaction.atomic():
        _update(customer, CUSTOMER_FIELDS, fields)
        if changing:
            record_audit(
                user=actor, action="customer.opening_balance_update", entity="Customer",
                entity_id=customer.pk,
                before={"opening_balance_cents": before},
                after={"opening_balance_cents": customer.opening_balance_cents},
            )
    return customer


# --- Prices (Doc 1 s4.2, s4.3; Doc 2 s8: every price change is audited) ---------------------

PRICE_DEFAULT_UPDATE_FIELDS = {"unit_price_cents"}
PRICE_OVERRIDE_UPDATE_FIELDS = {"unit_price_cents", "note", "is_active"}


def _check_price(fields):
    if "unit_price_cents" in fields:
        cents = fields["unit_price_cents"]
        if type(cents) is not int or cents <= 0:
            raise ValueError("unit_price_cents must be an integer greater than 0")


def _check_effective_from(fields):
    if "effective_from" in fields:
        value = fields["effective_from"]
        if not isinstance(value, date) or isinstance(value, datetime):
            raise ValueError("effective_from must be a date")


def _snapshot(obj, names):
    """JSON-safe copy of a price row for the audit log (UUIDs as text, dates as ISO)."""
    data = {"id": str(obj.pk)}
    for name in names:
        value = getattr(obj, name)
        if isinstance(value, date):
            value = value.isoformat()
        elif name.endswith("_id"):
            value = str(value)
        data[name] = value
    return data


DEFAULT_SNAPSHOT = ("product_id", "customer_type_id", "unit_price_cents", "effective_from")
OVERRIDE_SNAPSHOT = (
    "customer_id", "product_id", "unit_price_cents", "effective_from", "note", "is_active",
)


def _today(today):
    return today if today is not None else timezone.localdate()


def _check_later_than_current(rows, effective_from, today):
    """Doc 1 s4.3: a new price row must be dated after the row now in effect."""
    in_effect = [row[0] for row in rows if row[0] <= today]
    if in_effect and effective_from <= max(in_effect):
        raise PriceHistoryError(
            f"A new price needs an effective_from later than {max(in_effect).isoformat()}, "
            "the date of the price now in effect. To change a price, create a new row with "
            "a later effective_from."
        )


def _check_editable(price, fields, today):
    """A price in effect (effective_from today or earlier) keeps its unit_price_cents."""
    changing = "unit_price_cents" in fields and fields["unit_price_cents"] != price.unit_price_cents
    if changing and price.effective_from <= today:
        raise PriceHistoryError(
            f"The price effective {price.effective_from.isoformat()} is already in effect and "
            "cannot be changed. Create a new row with a later effective_from."
        )


def _require(fields, names):
    missing = [name for name in names if name not in fields]
    if missing:
        raise ValueError(f"Missing fields: {missing}")


def create_price_default(*, actor, today=None, **fields):
    require_admin(actor)
    allowed = {"product", "customer_type", "unit_price_cents", "effective_from"}
    _check_fields(fields, allowed)
    _require(fields, allowed)
    _check_price(fields)
    _check_effective_from(fields)
    with transaction.atomic():
        _check_later_than_current(
            selectors.default_rows(fields["customer_type"].pk, fields["product"]),
            fields["effective_from"], _today(today),
        )
        price = _create(PriceDefault, allowed, fields)
        record_audit(
            user=actor, action="price_default.create", entity="PriceDefault",
            entity_id=price.pk, after=_snapshot(price, DEFAULT_SNAPSHOT),
        )
    return price


def update_price_default(price, *, actor, today=None, **fields):
    require_admin(actor)
    _check_fields(fields, PRICE_DEFAULT_UPDATE_FIELDS)
    _check_price(fields)
    with transaction.atomic():
        _check_editable(price, fields, _today(today))
        before = _snapshot(price, DEFAULT_SNAPSHOT)
        _update(price, PRICE_DEFAULT_UPDATE_FIELDS, fields)
        record_audit(
            user=actor, action="price_default.update", entity="PriceDefault",
            entity_id=price.pk, before=before, after=_snapshot(price, DEFAULT_SNAPSHOT),
        )
    return price


def create_price_override(*, actor, today=None, **fields):
    require_admin(actor)
    allowed = {"customer", "product", "unit_price_cents", "effective_from", "note", "is_active"}
    _check_fields(fields, allowed)
    _require(fields, {"customer", "product", "unit_price_cents", "effective_from"})
    _check_price(fields)
    _check_effective_from(fields)
    with transaction.atomic():
        _check_later_than_current(
            selectors.override_rows(fields["customer"], fields["product"]),
            fields["effective_from"], _today(today),
        )
        price = _create(PriceOverride, allowed, fields)
        record_audit(
            user=actor, action="price_override.create", entity="PriceOverride",
            entity_id=price.pk, after=_snapshot(price, OVERRIDE_SNAPSHOT),
        )
    return price


def update_price_override(price, *, actor, today=None, **fields):
    require_admin(actor)
    _check_fields(fields, PRICE_OVERRIDE_UPDATE_FIELDS)
    _check_price(fields)
    with transaction.atomic():
        _check_editable(price, fields, _today(today))
        before = _snapshot(price, OVERRIDE_SNAPSHOT)
        _update(price, PRICE_OVERRIDE_UPDATE_FIELDS, fields)
        record_audit(
            user=actor, action="price_override.update", entity="PriceOverride",
            entity_id=price.pk, before=before, after=_snapshot(price, OVERRIDE_SNAPSHOT),
        )
    return price
