"""All reads for products, customer types, customers, prices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from .models import PriceDefault, PriceOverride


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
