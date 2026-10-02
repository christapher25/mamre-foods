"""All reads for invoices, payments, allocations, balances.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from django.core.exceptions import ValidationError
from django.db.models import Sum

from apps.catalog import selectors as catalog_selectors

from . import rules
from .models import (
    Invoice,
    InvoiceItem,
    InvoiceStatus,
    Payment,
    PaymentAllocation,
    SalesCounter,
)


def current_sales_cursor():
    """The sales counter as it stands now (the ceiling for customer-activity, Doc 2 s6.4)."""
    return SalesCounter.objects.values_list("value", flat=True).first() or 0


def get_invoice(invoice_id):
    """The invoice with this id, or None. A malformed id is None, never an error."""
    try:
        return Invoice.objects.filter(pk=invoice_id).first()
    except (ValueError, TypeError, ValidationError):
        return None


def get_payment(payment_id):
    try:
        return Payment.objects.filter(pk=payment_id).first()
    except (ValueError, TypeError, ValidationError):
        return None


def invoice_number_taken(number):
    return Invoice.objects.filter(number=number).exists()


def allocations_for_payment(payment):
    """[(invoice_id, amount_cents)] for one payment, in the order they were made."""
    return list(
        PaymentAllocation.objects.filter(payment=payment)
        .order_by("created_at", "id")
        .values_list("invoice_id", "amount_cents")
    )


def invoice_paid_cents(invoice):
    """Cents already allocated to an invoice."""
    return PaymentAllocation.objects.filter(invoice=invoice).aggregate(s=Sum("amount_cents"))[
        "s"
    ] or 0


def open_invoices(customer):
    """[(invoice_id, remaining_cents)] for the customer's non-void invoices that still owe
    money, oldest first (Doc 1 s6.2): issued_at, then number for a stable order."""
    paid = dict(
        PaymentAllocation.objects.filter(invoice__customer=customer)
        .values("invoice_id")
        .annotate(s=Sum("amount_cents"))
        .values_list("invoice_id", "s")
    )
    rows = (
        Invoice.objects.filter(customer=customer, status=InvoiceStatus.ACTIVE)
        .order_by("issued_at", "number")
        .values_list("id", "total_cents")
    )
    return [(pk, total - paid.get(pk, 0)) for pk, total in rows if total - paid.get(pk, 0) > 0]


def customer_balance(customer, *, ceiling=None):
    """opening + non-void invoices - payments (Doc 1 s6.3, Doc 2 I-6). Always computed.

    With a ceiling, only rows at or below that sales sync_version count, so the balance
    matches the activity pull that returned that cursor. Credits arrive in P4."""
    invoices = Invoice.objects.filter(customer=customer, status=InvoiceStatus.ACTIVE)
    payments = Payment.objects.filter(customer=customer)
    if ceiling is not None:
        invoices = invoices.filter(sync_version__lte=ceiling)
        payments = payments.filter(sync_version__lte=ceiling)
    invoiced = invoices.aggregate(s=Sum("total_cents"))["s"] or 0
    paid = payments.aggregate(s=Sum("amount_cents"))["s"] or 0
    return rules.balance(customer.opening_balance_cents, invoiced, paid)


def activity_since(cursor, ceiling):
    """Credit-customer activity with cursor < sales sync_version <= ceiling (Doc 2 s5, s6.4).

    Returns [{customer, invoices, payments, allocations, balance_cents}] for every credit
    customer with at least one changed row; rows are only the changed ones. A void bumps the
    invoice's version, so it shows up here. Balances use the same ceiling."""
    in_range = {"sync_version__gt": cursor, "sync_version__lte": ceiling}
    invoices = list(
        Invoice.objects.filter(customer__isnull=False, **in_range)
        .prefetch_related("items")
        .order_by("sync_version", "issued_at")
    )
    payments = list(
        Payment.objects.filter(customer__isnull=False, **in_range).order_by("sync_version")
    )
    allocations = list(
        PaymentAllocation.objects.filter(payment__customer__isnull=False, **in_range)
        .select_related("payment")
        .order_by("sync_version")
    )
    changed = {i.customer_id for i in invoices}
    changed |= {p.customer_id for p in payments}
    changed |= {a.payment.customer_id for a in allocations}

    result = []
    for customer in catalog_selectors.credit_customers():
        if customer.pk not in changed:
            continue
        result.append(
            {
                "customer": customer,
                "invoices": [i for i in invoices if i.customer_id == customer.pk],
                "payments": [p for p in payments if p.customer_id == customer.pk],
                "allocations": [a for a in allocations if a.payment.customer_id == customer.pk],
                "balance_cents": customer_balance(customer, ceiling=ceiling),
            }
        )
    return result


def invoice_items(invoice):
    return list(InvoiceItem.objects.filter(invoice=invoice).order_by("created_at", "id"))
