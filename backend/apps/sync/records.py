"""Pure parsing and validation of pushed records: no database (Doc 3 s7.2).

Doc 2 s5.1, s5.2, I-2, I-3; Doc 1 s5.2, s6.1. Every failure is a Reject with a stable code and
a retryable flag: retryable is true only for dependency problems that may clear on a later push
(created in services.py); every failure found here is a validation failure, so it is false.
"""
import uuid
from dataclasses import dataclass

from django.utils.dateparse import parse_datetime

from apps.sales import rules

METHODS = ("cash", "zelle", "check", "card", "other")
# Upper bounds (openapi.yaml). Anything above is a rejected record, never a database overflow.
MAX_CENTS = 1_000_000_000  # unit_price_cents, amount_cents and total_cents: $10,000,000.00
MAX_QTY = 100_000  # qty_packets


@dataclass
class Reject(Exception):
    code: str
    message: str
    retryable: bool = False


def parse_uuid(value, name):
    if isinstance(value, uuid.UUID):
        return str(value)
    if not isinstance(value, str):
        raise Reject("validation_error", f"{name} must be a UUID string.")
    try:
        return str(uuid.UUID(value))
    except ValueError:
        raise Reject("validation_error", f"{name} is not a valid UUID.") from None


def parse_int(value, name, maximum):
    """A strict integer from 1 to maximum: no bool, no float, no numeric string (Doc 2 I-1)."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise Reject("validation_error", f"{name} must be a whole number.")
    if value <= 0:
        raise Reject("validation_error", f"{name} must be greater than zero.")
    if value > maximum:
        raise Reject("validation_error", f"{name} must be at most {maximum}.")
    return value


def parse_time(value, name):
    parsed = parse_datetime(value) if isinstance(value, str) else None
    if parsed is None or parsed.tzinfo is None:
        raise Reject("validation_error", f"{name} must be an ISO 8601 time with a UTC offset.")
    return parsed


def parse_invoice(raw, device_code):
    """Validate one pushed invoice. Returns plain data with ids and product/customer ids still
    to be resolved by the caller. Walk-in payment rules need the payments, so they live in
    services.py. Raises Reject."""
    if not isinstance(raw, dict):
        raise Reject("validation_error", "An invoice must be an object.")
    number = raw.get("number")
    found = rules.number_device_code(number)
    if found is None:
        raise Reject("invalid_number", "Invoice number must look like MAM-W1-0042.")
    if found != device_code:
        raise Reject(
            "wrong_device", f"Invoice number {number} does not belong to device {device_code}."
        )
    customer_id = raw.get("customer_id")
    if customer_id is not None:
        customer_id = parse_uuid(customer_id, "customer_id")
    issued_at = parse_time(raw.get("issued_at"), "issued_at")
    items_raw = raw.get("items")
    if not isinstance(items_raw, list) or not items_raw:
        raise Reject("validation_error", "An invoice needs at least one item.")
    items, seen = [], set()
    for entry in items_raw:
        if not isinstance(entry, dict):
            raise Reject("validation_error", "An item must be an object.")
        item_id = parse_uuid(entry.get("id"), "item id")
        if item_id in seen:
            raise Reject("validation_error", "Item ids must be unique within an invoice.")
        seen.add(item_id)
        items.append(
            {
                "id": item_id,
                "product_id": parse_uuid(entry.get("product_id"), "product_id"),
                "qty_packets": parse_int(entry.get("qty_packets"), "qty_packets", MAX_QTY),
                "unit_price_cents": parse_int(
                    entry.get("unit_price_cents"), "unit_price_cents", MAX_CENTS
                ),
            }
        )
    total = parse_int(raw.get("total_cents"), "total_cents", MAX_CENTS)
    expected = sum(rules.line_total(i["qty_packets"], i["unit_price_cents"]) for i in items)
    if total != expected:
        raise Reject(
            "total_mismatch", f"total_cents is {total} but the lines add up to {expected}."
        )
    return {
        "number": number,
        "customer_id": customer_id,
        "issued_at": issued_at,
        "items": items,
        "total_cents": total,
    }


def parse_payment(raw):
    """Validate one pushed payment (Doc 1 s6.1; needs customer_id or invoice_id)."""
    if not isinstance(raw, dict):
        raise Reject("validation_error", "A payment must be an object.")
    amount = parse_int(raw.get("amount_cents"), "amount_cents", MAX_CENTS)
    method = raw.get("method")
    if method not in METHODS:
        raise Reject("validation_error", f"method must be one of {', '.join(METHODS)}.")
    note = raw.get("note", "")
    if note is None:
        note = ""
    if not isinstance(note, str):
        raise Reject("validation_error", "note must be text.")
    if method == "other" and not note.strip():
        raise Reject("validation_error", "A payment with method other needs a note.")
    receipt = raw.get("receipt_number")
    if receipt is not None and (not isinstance(receipt, str) or len(receipt) > 40):
        raise Reject("validation_error", "receipt_number must be text of at most 40 characters.")
    customer_id, invoice_id = raw.get("customer_id"), raw.get("invoice_id")
    if customer_id is None and invoice_id is None:
        raise Reject("missing_target", "A payment needs customer_id or invoice_id.")
    return {
        "amount_cents": amount,
        "method": method,
        "note": note,
        "receipt_number": receipt or None,
        "paid_at": parse_time(raw.get("paid_at"), "paid_at"),
        "customer_id": None if customer_id is None else parse_uuid(customer_id, "customer_id"),
        "invoice_id": None if invoice_id is None else parse_uuid(invoice_id, "invoice_id"),
    }


def walk_in_paid_cents(invoice_id, payments_raw, is_stored=lambda payment_id: False):
    """Cents of the pushed payments that count toward a walk-in invoice (Doc 1 s4.1).

    Only a payment that passes full validation counts: parse_payment (amount, method, a note
    for other, bounds) and no customer_id at all, because a walk-in has no customer, so any
    customer_id is a customer_mismatch or an unknown_customer in services. Each payment id
    counts once. A payment id that is already stored does not count (is_stored(id) is true):
    it will be answered "duplicate" for its original invoice, so it can never pay this one.
    The caller validates before it stores anything."""
    seen, total = set(), 0
    for raw in payments_raw:
        if not isinstance(raw, dict):
            continue
        try:
            payment_id = parse_uuid(raw.get("id"), "id")
            data = parse_payment(raw)
        except Reject:
            continue
        if data["invoice_id"] != invoice_id or data["customer_id"] is not None:
            continue
        if payment_id in seen or is_stored(payment_id):
            continue
        seen.add(payment_id)
        total += data["amount_cents"]
    return total
