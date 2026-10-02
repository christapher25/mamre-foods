"""Worker-facing output for customer-activity (Doc 2 s8, I-8).

Every key is written out by name, so cost, profit and expense data cannot leak through a
shared model serializer. No float is used anywhere: money stays integer cents (I-1).
"""


def _iso(value):
    return value.strftime("%Y-%m-%dT%H:%M:%SZ") if value else None


def activity_invoice(invoice):
    return {
        "id": str(invoice.pk),
        "number": invoice.number,
        "issued_at": _iso(invoice.issued_at),
        "total_cents": invoice.total_cents,
        "status": invoice.status,
        "items": [
            {
                "id": str(item.pk),
                "product_id": str(item.product_id),
                "product_name": item.product_name,
                "qty_packets": item.qty_packets,
                "unit_price_cents": item.unit_price_cents,
                "line_total_cents": item.line_total_cents,
            }
            for item in invoice.items.all()
        ],
    }


def activity_payment(payment):
    return {
        "id": str(payment.pk),
        "invoice_id": str(payment.invoice_id) if payment.invoice_id else None,
        "receipt_number": payment.receipt_number or None,
        "amount_cents": payment.amount_cents,
        "method": payment.method,
        "paid_at": _iso(payment.paid_at),
    }


def activity_allocation(allocation):
    return {
        "id": str(allocation.pk),
        "payment_id": str(allocation.payment_id),
        "invoice_id": str(allocation.invoice_id),
        "amount_cents": allocation.amount_cents,
    }


def activity_customer(entry):
    customer = entry["customer"]
    return {
        "customer_id": str(customer.pk),
        "opening_balance_cents": customer.opening_balance_cents,
        "balance_cents": entry["balance_cents"],
        "invoices": [activity_invoice(i) for i in entry["invoices"]],
        "payments": [activity_payment(p) for p in entry["payments"]],
        "allocations": [activity_allocation(a) for a in entry["allocations"]],
    }
