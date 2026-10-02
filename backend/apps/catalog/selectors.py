"""All reads for products, customer types, customers, prices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from .models import (
    Customer,
    CustomerType,
    PriceDefault,
    PriceOverride,
    Product,
    SyncCounter,
)


def active_override_rows(customer, product):
    """(effective_from, unit_price_cents) for the customer's active overrides on a product."""
    return list(
        PriceOverride.objects.filter(customer=customer, product=product, is_active=True)
        .values_list("effective_from", "unit_price_cents")
    )


def default_rows(customer_type_id, product):
    """(effective_from, unit_price_cents) for a customer type's default prices on a product."""
    return list(
        PriceDefault.objects.filter(customer_type_id=customer_type_id, product=product)
        .values_list("effective_from", "unit_price_cents")
    )


# Business header settings a worker may receive (Doc 2 s5; P-6 pending values stay unset).
HEADER_SETTING_KEYS = ("business_name", "address", "phone", "footer_text")


def changes_since(cursor):
    """Every catalog row with cursor < sync_version <= counter, inactive rows included.

    The SyncCounter is read once, first. A version at or below it belongs to a transaction
    that has already committed (the counter row is locked until commit, see
    services.next_sync_version), so nothing at or below it can appear later. Rows above it
    are left for the next pull. The new cursor is that counter value (Doc 2 s6.4).
    """
    from apps.accounts.selectors import settings_changed_since

    ceiling = SyncCounter.objects.values_list("value", flat=True).first() or 0

    def changed(model):
        return list(
            model.objects.filter(sync_version__gt=cursor, sync_version__lte=ceiling)
            .order_by("sync_version")
        )

    return {
        "products": changed(Product),
        "customer_types": changed(CustomerType),
        "customers": changed(Customer),
        "price_defaults": changed(PriceDefault),
        "price_overrides": changed(PriceOverride),
        "settings": settings_changed_since(cursor, HEADER_SETTING_KEYS, ceiling),
        "cursor": ceiling,
    }
