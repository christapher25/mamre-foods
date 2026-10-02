"""Shared helpers for A2 (sales and sync) tests. No tests live here."""
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

from apps.accounts.models import Device
from apps.catalog.models import Customer, CustomerType, Product
from apps.catalog.services import create_customer
from tests.core_helpers import make_user, token_client


def utc(day, hour=12, month=10):
    return datetime(2026, month, day, hour, 0, tzinfo=timezone.utc)


@dataclass
class World:
    worker: object
    device: Device
    client: object
    customer: Customer  # credit, opening balance 0
    chapathi: Product
    fresh: Product


def build_world(username="w1user", code="W1"):
    worker = make_user(username)
    device = Device.objects.create(code=code, user=worker)
    restaurant = CustomerType.objects.get(name="Restaurant")
    customer = create_customer(name="Spice Garden", type=restaurant, payment_mode="credit")
    return World(
        worker=worker,
        device=device,
        client=token_client(worker),
        customer=customer,
        chapathi=Product.objects.get(code="CHAPATHI"),
        fresh=Product.objects.get(code="FRESH"),
    )


def invoice_payload(world, number, total, *, customer="default", day=3, qty=None, price=100,
                    inv_id=None, product=None):
    """One-line invoice of `total` cents: qty x price (price defaults to 100 cents)."""
    qty = qty if qty is not None else total // price
    cust = world.customer if customer == "default" else customer
    return {
        "id": str(inv_id or uuid.uuid4()),
        "number": number,
        "customer_id": str(cust.id) if cust else None,
        "issued_at": utc(day).isoformat(),
        "items": [{
            "id": str(uuid.uuid4()),
            "product_id": str((product or world.chapathi).id),
            "qty_packets": qty,
            "unit_price_cents": price,
        }],
        "total_cents": qty * price,
    }


def payment_payload(amount, *, invoice_id=None, customer=None, day=12, method="cash",
                    pay_id=None, **extra):
    body = {
        "id": str(pay_id or uuid.uuid4()),
        "amount_cents": amount,
        "method": method,
        "paid_at": utc(day).isoformat(),
    }
    if invoice_id:
        body["invoice_id"] = str(invoice_id)
    if customer is not None:
        body["customer_id"] = str(customer.id)
    body.update(extra)
    return body


def push_body(world, invoices=(), payments=(), returns=()):
    return {
        "device_code": world.device.code,
        "invoices": list(invoices),
        "payments": list(payments),
        "returns": list(returns),
    }
