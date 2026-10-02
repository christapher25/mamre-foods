"""Pure business rules: no database, no Django (Doc 3 s7.2). Money is integer cents.

Doc 2 I-2, I-3, I-5; Doc 1 s6.2, s6.3.
"""
import re

NUMBER_RE = re.compile(r"^MAM-([A-Z0-9]+)-([0-9]{4,})$")  # Doc 2 I-3


def number_device_code(number):
    """The device code inside an invoice number, or None if the format is wrong (I-3)."""
    match = NUMBER_RE.match(number) if isinstance(number, str) else None
    return match.group(1) if match else None


def line_total(qty_packets, unit_price_cents):
    """Line total in cents (Doc 2 I-2). Whole packets times whole cents is exact: no rounding."""
    return qty_packets * unit_price_cents


def allocate(amount_cents, open_invoices):
    """Apply a payment to unpaid invoices, oldest first, spilling to the next (Doc 1 s6.2).

    open_invoices: [(invoice_id, remaining_cents)] already ordered oldest first.
    Returns (allocations [(invoice_id, cents)], remainder_cents). The remainder is credit on
    account (Doc 1 A-4, Doc 2 I-5).
    """
    left = amount_cents
    allocations = []
    for invoice_id, remaining in open_invoices:
        if left <= 0:
            break
        if remaining <= 0:
            continue
        take = min(left, remaining)
        allocations.append((invoice_id, take))
        left -= take
    return allocations, left


def balance(opening_cents, invoiced_cents, paid_cents):
    """Opening + non-void invoices - payments (Doc 1 s6.3; credits arrive in P4)."""
    return opening_cents + invoiced_cents - paid_cents
