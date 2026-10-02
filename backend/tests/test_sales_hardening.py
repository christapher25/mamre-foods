"""Reviewer findings for the sales app (Doc 2 I-3, I-4, I-9, s6.4)."""
import pytest

from apps.accounts.models import AuditLog
from apps.sales import rules, services
from apps.sales.models import Invoice, InvoiceItem, Payment, PaymentAllocation, void_permit
from tests.core_helpers import make_user
from tests.test_sales_helpers import build_world, utc

pytestmark = pytest.mark.django_db

MODELS = (Invoice, InvoiceItem, Payment, PaymentAllocation)

BULK_CREATE_KWARGS = [
    {},
    {"ignore_conflicts": True},
    {"update_conflicts": True, "unique_fields": ["id"], "update_fields": ["sync_version"]},
]


@pytest.mark.parametrize("model", MODELS)
@pytest.mark.parametrize("kwargs", BULK_CREATE_KWARGS, ids=["plain", "ignore", "update"])
def test_bulk_create_is_refused_on_every_sales_model(model, kwargs):
    with pytest.raises(RuntimeError):
        model.objects.bulk_create([model()], **kwargs)
    with pytest.raises(RuntimeError):
        model.objects.all().bulk_create([], **kwargs)  # even an empty list
    assert model.objects.count() == 0


# ---- finding 2: a void is impossible without its AuditLog row --------------------------------

def _invoice(world, number="MAM-W1-0001"):
    return services.store_invoice(
        worker=world.worker, device=world.device,
        data={
            "id": "11111111-1111-4111-8111-111111111111", "number": number,
            "customer": world.customer, "issued_at": utc(3),
            "items": [{"id": "22222222-2222-4222-8222-222222222222",
                       "product": world.chapathi, "qty_packets": 2, "unit_price_cents": 500}],
            "total_cents": 1000,
        },
    )


def _status(invoice):
    return Invoice.objects.values_list("status", flat=True).get(pk=invoice.pk)


def test_apply_void_is_private_and_unusable_outside_the_service():
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    assert not hasattr(invoice, "apply_void")
    with pytest.raises(RuntimeError):
        invoice._apply_void(actor=admin, reason="sneaky", at=utc(4), sync_version=99)
    assert _status(invoice) == "active"
    assert invoice.status == "active"  # the object was not touched either


def test_a_permit_for_another_invoice_does_not_open_the_door():
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    audit = AuditLog.objects.create(user=admin, action="invoice.void", entity="invoice",
                                    entity_id="not-this-invoice")
    with void_permit("33333333-3333-4333-8333-333333333333", audit):
        with pytest.raises(RuntimeError):
            invoice._apply_void(actor=admin, reason="x", at=utc(4), sync_version=99)
    assert _status(invoice) == "active"


def test_no_audit_row_means_no_void_when_the_audit_write_fails(monkeypatch):
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")

    def boom(**kwargs):
        raise RuntimeError("audit store down")

    monkeypatch.setattr(services, "record_audit", boom)
    with pytest.raises(RuntimeError):
        services.void_invoice(invoice, actor=admin, reason="Mistake")
    assert _status(invoice) == "active"
    assert not AuditLog.objects.filter(action="invoice.void").exists()


def test_no_audit_row_means_no_void_when_no_row_comes_back(monkeypatch):
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    monkeypatch.setattr(services, "record_audit", lambda **kwargs: None)
    with pytest.raises(RuntimeError):
        services.void_invoice(invoice, actor=admin, reason="Mistake")
    assert _status(invoice) == "active"


def test_a_void_always_leaves_exactly_one_audit_row():
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    services.void_invoice(invoice, actor=admin, reason="Mistake")
    assert _status(invoice) == "void"
    assert AuditLog.objects.filter(action="invoice.void", entity_id=str(invoice.pk)).count() == 1


# ---- finding 3: the invoice number must match in full ----------------------------------------

@pytest.mark.parametrize("number", [
    "MAM-W1-0042\n", "MAM-W1-0042\r\n", " MAM-W1-0042", "MAM-W1-0042 ", "\tMAM-W1-0042",
    "mam-w1-0042", "MAM-w1-0042", "MAM-W1-0042\n\n",
])
def test_number_with_whitespace_or_lowercase_is_rejected(number):
    assert rules.number_device_code(number) is None


def test_exact_number_is_still_accepted():
    assert rules.number_device_code("MAM-W1-0042") == "W1"
