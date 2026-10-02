"""All writes for invoices, payments, allocations, returns, ledger.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
Nothing here edits or deletes a financial row; the only change is invoice active to void
(Doc 1 s5.4, Doc 2 I-4, I-9).
"""
from django.db import transaction
from django.utils import timezone

from apps.accounts.services import record_audit, require_admin

from . import rules, selectors
from .models import (
    Invoice,
    InvoiceItem,
    InvoiceStatus,
    Payment,
    PaymentAllocation,
    SalesCounter,
    void_permit,
)


def next_sales_version():
    """The next monotonic sales cursor value (Doc 2 s6.4). Call inside the writing transaction.

    The counter row stays locked until that transaction commits, so versions become visible in
    order (on PostgreSQL) and a reader that reads the counter first never skips a row. Taking it
    first in every write also serialises sales writes, so two pushes cannot allocate the same
    invoice twice. SQLite (tests only) serialises writers anyway.

    LOCK ORDER RULE: every sales writer (store_invoice, store_payment, void_invoice and any
    future one) takes this counter lock FIRST, and only then locks or writes any other sales
    row. One fixed order means two writers can never wait on each other (no deadlock), and the
    void path cannot lock an invoice while a payment holds the counter and wants that invoice.
    tests/test_sales_hardening.py checks the order from the query log."""
    with transaction.atomic():
        counter, _ = SalesCounter.objects.select_for_update().get_or_create(pk=1)
        counter.value += 1
        counter.save(update_fields=["value"])
        return counter.value


@transaction.atomic
def store_invoice(*, worker, device, data):
    """Store one already-validated invoice with its items (Doc 2 s4.2). The price on each item
    is the device snapshot, stored as sent, never repriced (Doc 1 s4.3, Doc 2 I-7).

    data: id, number, customer (object or None), issued_at, items [{id, product, qty_packets,
    unit_price_cents}], total_cents."""
    version = next_sales_version()
    customer = data["customer"]
    now = timezone.now()
    invoice = Invoice.objects.create(
        id=data["id"],
        number=data["number"],
        customer=customer,
        customer_name=customer.name if customer else "",
        customer_type=customer.type.name if customer else "",
        worker=worker,
        device=device,
        issued_at=data["issued_at"],
        total_cents=data["total_cents"],
        client_created_at=data["issued_at"],
        server_received_at=now,
        sync_version=version,
    )
    for item in data["items"]:
        InvoiceItem.objects.create(
            id=item["id"],
            invoice=invoice,
            product=item["product"],
            product_name=item["product"].name,
            qty_packets=item["qty_packets"],
            unit_price_cents=item["unit_price_cents"],
            line_total_cents=rules.line_total(item["qty_packets"], item["unit_price_cents"]),
            sync_version=version,
        )
    return invoice


@transaction.atomic
def store_payment(*, worker, device, data):
    """Store one already-validated payment and, for a customer, allocate it oldest invoice
    first (Doc 1 s6.2). A walk-in payment (no customer) is never allocated (Doc 1 s4.1).
    The remainder stays as credit on account (Doc 1 A-4, Doc 2 I-5).

    data: id, customer (object or None), invoice (object or None), receipt_number,
    amount_cents, method, paid_at, note. Returns (payment, [(invoice_id, cents)])."""
    version = next_sales_version()
    payment = Payment.objects.create(
        id=data["id"],
        receipt_number=data.get("receipt_number") or "",
        customer=data["customer"],
        invoice=data["invoice"],
        amount_cents=data["amount_cents"],
        method=data["method"],
        paid_at=data["paid_at"],
        worker=worker,
        device=device,
        note=data.get("note", ""),
        client_created_at=data["paid_at"],
        server_received_at=timezone.now(),
        sync_version=version,
    )
    allocations = []
    if payment.customer is not None:
        allocations, _credit = rules.allocate(
            payment.amount_cents, selectors.open_invoices(payment.customer)
        )
        for invoice_id, cents in allocations:
            PaymentAllocation.objects.create(
                payment=payment, invoice_id=invoice_id, amount_cents=cents, sync_version=version
            )
    return payment, allocations


@transaction.atomic
def void_invoice(invoice, *, actor, reason):
    """The only change an invoice ever gets: active to void (Doc 1 s5.4, Doc 2 I-4).

    Needs an active admin (Doc 1 s2) and a reason; writes an AuditLog row in the same
    transaction (Doc 2 s8). The number and every other field stay as they were."""
    require_admin(actor)
    reason = (reason or "").strip()
    if not reason:
        raise ValueError("A reason is required to void an invoice.")
    version = next_sales_version()  # lock order: the counter FIRST, then the invoice row
    current = Invoice.objects.select_for_update().get(pk=invoice.pk)
    if current.status != InvoiceStatus.ACTIVE:
        raise ValueError("Only an active invoice can be voided.")
    at = timezone.now()
    audit = record_audit(
        user=actor,
        action="invoice.void",
        entity="invoice",
        entity_id=invoice.pk,
        before={"status": InvoiceStatus.ACTIVE, "void_reason": ""},
        after={
            "status": InvoiceStatus.VOID,
            "void_reason": reason,
            "voided_by": str(actor.pk),
            "voided_at": at.isoformat(),
        },
    )
    with void_permit(invoice.pk, audit):  # refuses to open without the audit row
        invoice._apply_void(actor=actor, reason=reason, at=at, sync_version=version)
    return invoice
