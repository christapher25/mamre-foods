"""All writes for mobile sync endpoints.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.

push_batch (Doc 2 s5.1, s5.2, s6, I-11): every record is processed on its own. Each stored
record commits in its own transaction inside apps.sales.services, so one bad record never
blocks the others, and sending the same UUID twice stores one record.
"""
from django.db import DataError, IntegrityError
from django.utils import timezone
from rest_framework.exceptions import ValidationError

from apps.accounts import selectors as accounts_selectors
from apps.catalog import selectors as catalog_selectors
from apps.sales import selectors as sales_selectors
from apps.sales import services as sales_services

from . import records
from .models import SyncBatchLog
from .records import Reject

MAX_RECORDS = 200  # per push, all lists together (openapi.yaml)


def _id_of(raw):
    """The id to echo in a result, even when the record is malformed."""
    value = raw.get("id") if isinstance(raw, dict) else None
    try:
        return records.parse_uuid(value, "id")
    except Reject:
        return value if isinstance(value, str) else ""


def _result(kind, record_id, status, **extra):
    return {"type": kind, "id": record_id, "status": status, **extra}


def _rejected(kind, record_id, reject):
    return _result(
        kind,
        record_id,
        "rejected",
        reason={
            "code": reject.code,
            "message": reject.message,
            "retryable": reject.retryable,
        },
    )


def _allocated(pairs):
    return [{"invoice_id": str(i), "amount_cents": c} for i, c in pairs]


def _push_invoice(raw, *, worker, device, payments_raw):
    record_id = records.parse_uuid(raw.get("id") if isinstance(raw, dict) else None, "id")
    if sales_selectors.get_invoice(record_id) is not None:
        return _result("invoice", record_id, "duplicate")
    data = records.parse_invoice(raw, device.code)
    if sales_selectors.invoice_number_taken(data["number"]):
        raise Reject(
            "duplicate_number",
            f"Invoice number {data['number']} is already used by another invoice.",
        )
    customer = None
    if data["customer_id"] is not None:
        customer = catalog_selectors.get_customer(data["customer_id"])
        if customer is None:
            raise Reject("unknown_customer", "customer_id does not match any customer.")
    for item in data["items"]:
        item["product"] = catalog_selectors.get_product(item["product_id"])
        if item["product"] is None:
            raise Reject("unknown_product", "product_id does not match any product.")
    if customer is None:  # walk-in: must be paid in full at the time of sale (Doc 1 s4.1)
        paid = records.walk_in_paid_cents(record_id, payments_raw)
        if paid != data["total_cents"]:
            raise Reject(
                "walkin_not_fully_paid",
                f"A walk-in invoice must be paid in full: total {data['total_cents']}, "
                f"payments in this push {paid}.",
            )
    try:
        sales_services.store_invoice(
            worker=worker,
            device=device,
            data={**data, "id": record_id, "customer": customer},
        )
    except IntegrityError:
        if sales_selectors.get_invoice(record_id) is not None:  # lost a race: already stored
            return _result("invoice", record_id, "duplicate")
        raise Reject(
            "validation_error", "The record conflicts with data that is already stored."
        ) from None
    return _result("invoice", record_id, "accepted")


def _push_payment(raw, *, worker, device, dead_invoices):
    record_id = records.parse_uuid(raw.get("id") if isinstance(raw, dict) else None, "id")
    existing = sales_selectors.get_payment(record_id)
    if existing is not None:
        allocated = _allocated(sales_selectors.allocations_for_payment(existing))
        return _result("payment", record_id, "duplicate", allocated=allocated)
    data = records.parse_payment(raw)

    invoice = None
    if data["invoice_id"] is not None:
        invoice = sales_selectors.get_invoice(data["invoice_id"])
        if invoice is None:
            if data["invoice_id"] in dead_invoices:
                raise Reject(
                    "invoice_rejected", "The invoice for this payment was rejected in this push."
                )
            raise Reject(
                "invoice_not_found",
                "The invoice for this payment has not arrived yet; send it with the payment.",
                retryable=True,
            )
    customer = None
    if data["customer_id"] is not None:
        customer = catalog_selectors.get_customer(data["customer_id"])
        if customer is None:
            raise Reject("unknown_customer", "customer_id does not match any customer.")
        if invoice is not None and invoice.customer_id != customer.pk:
            raise Reject("customer_mismatch", "customer_id does not match the invoice's customer.")
    elif invoice is not None and invoice.customer_id is not None:
        customer = catalog_selectors.get_customer(invoice.customer_id)
    if customer is None and invoice is not None:
        # Walk-in payment: never allocated, and it may not exceed the invoice total.
        paid = sales_selectors.invoice_payments_cents(invoice) + data["amount_cents"]
        if paid > invoice.total_cents:
            raise Reject(
                "walkin_overpaid", "A walk-in invoice cannot be paid more than its total."
            )
    try:
        payment, allocations = sales_services.store_payment(
            worker=worker,
            device=device,
            data={**data, "id": record_id, "customer": customer, "invoice": invoice},
        )
    except IntegrityError:
        existing = sales_selectors.get_payment(record_id)
        if existing is not None:
            allocated = _allocated(sales_selectors.allocations_for_payment(existing))
            return _result("payment", record_id, "duplicate", allocated=allocated)
        raise Reject(
            "validation_error", "The record conflicts with data that is already stored."
        ) from None
    return _result("payment", record_id, "accepted", allocated=_allocated(allocations))


def _check_request(body):
    if not isinstance(body, dict):
        raise ValidationError({"body": ["Expected a JSON object."]})
    code = body.get("device_code")
    if not isinstance(code, str) or not code:
        raise ValidationError({"device_code": ["device_code is required."]})
    lists = {}
    for name in ("invoices", "payments", "returns"):
        value = body.get(name)
        if not isinstance(value, list):
            raise ValidationError({name: [f"{name} must be a list."]})
        lists[name] = value
    total = sum(len(v) for v in lists.values())
    if total > MAX_RECORDS:
        raise ValidationError(
            {"records": [f"At most {MAX_RECORDS} records per push; received {total}."]}
        )
    return code, lists


def push_batch(user, body):
    """Process one push from a worker (Doc 2 s5.1). Returns the response body.

    The device code must be one of this worker's own devices (I-3); anything else is a 400.
    Returns are not supported until P4: each one is rejected with a clear reason."""
    code, lists = _check_request(body)
    device = accounts_selectors.get_device(user, code)
    if device is None:
        raise ValidationError({"device_code": ["device_code is not a device of this worker."]})

    results = []
    dead_invoices = set()  # ids of invoices rejected for a validation reason in this push

    def run(kind, raw, function, **kwargs):
        try:
            result = function(raw, worker=user, device=device, **kwargs)
        except Reject as reject:
            result = _rejected(kind, _id_of(raw), reject)
        except (DataError, OverflowError):
            # A value the database cannot hold: this record fails, the others carry on.
            result = _rejected(
                kind,
                _id_of(raw),
                Reject("validation_error", "A value in this record is out of range."),
            )
        if kind == "invoice" and result["status"] == "rejected":
            dead_invoices.add(result["id"])  # no invoice rejection is retryable
        results.append(result)

    for raw in lists["invoices"]:
        run("invoice", raw, _push_invoice, payments_raw=lists["payments"])
    for raw in lists["payments"]:
        run("payment", raw, _push_payment, dead_invoices=dead_invoices)
    for raw in lists["returns"]:
        results.append(
            _rejected(
                "return",
                _id_of(raw),
                Reject("returns_not_supported", "Returns are not supported yet."),
            )
        )

    counts = {}
    for result in results:
        bucket = counts.setdefault(result["type"], {"accepted": 0, "duplicate": 0, "rejected": 0})
        bucket[result["status"]] += 1
    now = timezone.now()
    SyncBatchLog.objects.create(
        device=device,
        received_at=now,
        counts_json=counts,
        status="partial" if any(r["status"] == "rejected" for r in results) else "ok",
    )
    return {
        "results": results,
        "server_time": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "catalog_cursor": catalog_selectors.current_cursor(),
    }
