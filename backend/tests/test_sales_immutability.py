"""AT-10 and I-4, I-9: invoices, items, payments and allocations never change or vanish,
except an invoice going active to void through the service (Doc 2 s4.2, s12; Doc 1 s5.4)."""
import pytest

from apps.accounts.exceptions import AdminRequired
from apps.accounts.models import AuditLog
from apps.sales import services
from apps.sales.models import Invoice, InvoiceItem, Payment, PaymentAllocation
from tests.core_helpers import make_user
from tests.test_sales_helpers import build_world, utc

pytestmark = pytest.mark.django_db


def _stored(world):
    invoice = services.store_invoice(
        worker=world.worker, device=world.device,
        data={
            "id": "11111111-1111-4111-8111-111111111111", "number": "MAM-W1-0001",
            "customer": world.customer, "issued_at": utc(3),
            "items": [{
                "id": "22222222-2222-4222-8222-222222222222", "product": world.chapathi,
                "qty_packets": 2, "unit_price_cents": 500,
            }],
            "total_cents": 1000,
        },
    )
    payment, _ = services.store_payment(
        worker=world.worker, device=world.device,
        data={
            "id": "33333333-3333-4333-8333-333333333333", "customer": world.customer,
            "invoice": invoice, "receipt_number": None, "amount_cents": 400,
            "method": "cash", "paid_at": utc(4), "note": "",
        },
    )
    return invoice, payment


@pytest.fixture
def stored():
    world = build_world()
    invoice, payment = _stored(world)
    return world, invoice, payment


def test_models_refuse_update_and_delete(stored):
    _, invoice, payment = stored
    item = InvoiceItem.objects.get(invoice=invoice)
    allocation = PaymentAllocation.objects.get(payment=payment)
    for row in (invoice, item, payment, allocation):
        row.refresh_from_db()
        with pytest.raises(RuntimeError):
            row.save()
        with pytest.raises(RuntimeError):
            row.delete()


def test_querysets_refuse_update_delete_and_bulk_update(stored):
    for model in (Invoice, InvoiceItem, Payment, PaymentAllocation):
        with pytest.raises(RuntimeError):
            model.objects.update(id=model.objects.first().id)
        with pytest.raises(RuntimeError):
            model.objects.all().delete()
        with pytest.raises(RuntimeError):
            model.objects.bulk_update(list(model.objects.all()), ["id"])
    assert Invoice.objects.count() == 1 and Payment.objects.count() == 1


def test_changing_total_or_price_through_save_is_refused(stored):
    _, invoice, _ = stored
    invoice.total_cents = 1
    with pytest.raises(RuntimeError):
        invoice.save()
    invoice.refresh_from_db()
    assert invoice.total_cents == 1000
    with pytest.raises(RuntimeError):
        invoice.save(update_fields=["total_cents"])


def test_void_needs_active_admin_and_reason(stored):
    world, invoice, _ = stored
    with pytest.raises(AdminRequired):
        services.void_invoice(invoice, actor=world.worker, reason="oops")
    admin = make_user("boss", role="admin")
    with pytest.raises(ValueError):
        services.void_invoice(invoice, actor=admin, reason="   ")
    inactive = make_user("old", role="admin", is_active=False)
    with pytest.raises(AdminRequired):
        services.void_invoice(invoice, actor=inactive, reason="x")
    invoice.refresh_from_db()
    assert invoice.status == "active"
    assert not AuditLog.objects.filter(action="invoice.void").exists()


def test_void_changes_only_status_fields_and_audits(stored):
    world, invoice, _ = stored
    admin = make_user("boss", role="admin")
    before_version = invoice.sync_version
    services.void_invoice(invoice, actor=admin, reason="Wrong customer")
    invoice.refresh_from_db()
    assert invoice.status == "void"
    assert invoice.void_reason == "Wrong customer"
    assert invoice.voided_by_id == admin.id and invoice.voided_at is not None
    assert invoice.number == "MAM-W1-0001" and invoice.total_cents == 1000
    assert invoice.sync_version > before_version  # the device sees the void
    log = AuditLog.objects.get(action="invoice.void")
    assert log.entity_id == str(invoice.id) and log.user_id == admin.id
    assert log.before_json["status"] == "active" and log.after_json["status"] == "void"
    assert log.after_json["void_reason"] == "Wrong customer"


def test_void_is_one_way_and_not_repeatable(stored):
    world, invoice, _ = stored
    admin = make_user("boss", role="admin")
    services.void_invoice(invoice, actor=admin, reason="first")
    with pytest.raises(ValueError):
        services.void_invoice(invoice, actor=admin, reason="second")
    invoice.refresh_from_db()
    assert invoice.void_reason == "first"
    with pytest.raises(RuntimeError):
        invoice.save()
