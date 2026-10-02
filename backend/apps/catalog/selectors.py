"""All reads for products, customer types, customers, prices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from .models import Customer, CustomerType, PriceDefault, PriceOverride, Product


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
    """Every catalog row with sync_version above the cursor, inactive rows included.

    Returns the rows by kind and the new cursor: the highest version seen, or the incoming
    cursor when nothing changed (Doc 2 s6.4).
    """
    from apps.accounts.selectors import settings_changed_since

    def changed(model):
        return list(model.objects.filter(sync_version__gt=cursor).order_by("sync_version"))

    result = {
        "products": changed(Product),
        "customer_types": changed(CustomerType),
        "customers": changed(Customer),
        "price_defaults": changed(PriceDefault),
        "price_overrides": changed(PriceOverride),
        "settings": settings_changed_since(cursor, HEADER_SETTING_KEYS),
    }
    versions = [row.sync_version for rows in result.values() for row in rows]
    result["cursor"] = max(versions, default=cursor)
    return result
