"""Price resolution (Doc 1 s4.2, s4.3). Never defaults to zero.

pick_price is pure: plain (effective_from, unit_price_cents) pairs in, integer cents out.
resolve_price fetches the rows through selectors and calls it.
"""
from datetime import date

from . import selectors


class NoPriceError(Exception):
    """No price is set: the product cannot be invoiced ("No price set - contact admin")."""


def _latest_not_after(rows, at):
    effective = [row for row in rows if row[0] <= at]
    return max(effective, key=lambda row: row[0])[1] if effective else None


def pick_price(overrides, defaults, at: date) -> int:
    """Doc 1 s4.2: active override, else default for the customer type, else NoPriceError.

    Within each list the latest effective_from that is not after `at` wins (Doc 1 s4.3).
    Callers pass only active override rows.
    """
    price = _latest_not_after(overrides, at)
    if price is None:
        price = _latest_not_after(defaults, at)
    if price is None:
        raise NoPriceError("No price set - contact admin")
    return price


WALK_IN_TYPE_NAME = "Retail"


def resolve_price(customer, product, at: date) -> int:
    """Unit price in integer cents for this customer and product on date `at`.

    customer=None is a walk-in: no overrides, and the default price of the CustomerType
    named "Retail" (owner decision, Doc 1 s4.1, s4.2). Never returns zero; raises NoPriceError
    when the type or the price does not exist.
    """
    if customer is None:
        retail_id = selectors.customer_type_id_by_name(WALK_IN_TYPE_NAME)
        if retail_id is None:
            raise NoPriceError("No price set - contact admin")
        return pick_price([], selectors.default_rows(retail_id, product), at)
    return pick_price(
        selectors.active_override_rows(customer, product),
        selectors.default_rows(customer.type_id, product),
        at,
    )
