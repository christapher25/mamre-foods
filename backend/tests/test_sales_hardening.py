"""Reviewer findings for the sales app (Doc 2 I-3, I-4, I-9, s6.4)."""
import uuid
from types import SimpleNamespace

import pytest
from django.db import connection
from django.test.utils import CaptureQueriesContext

from apps.accounts.models import AuditLog
from apps.sales import rules, selectors, services
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

def _invoice(world, number="MAM-W1-0001", inv_id="11111111-1111-4111-8111-111111111111"):
    return services.store_invoice(
        worker=world.worker, device=world.device,
        data={
            "id": inv_id, "number": number,
            "customer": world.customer, "issued_at": utc(3),
            "items": [{"id": str(uuid.uuid4()),
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
    other = _invoice(world, number="MAM-W1-0002", inv_id="44444444-4444-4444-8444-444444444444")
    admin = make_user("boss", role="admin")
    audit = AuditLog.objects.create(user=admin, action="invoice.void", entity="invoice",
                                    entity_id=str(other.pk))
    with void_permit(other.pk, audit):  # a real permit, but for the other invoice
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


# ---- finding 6: every sales writer takes the counter lock first ------------------------------

def _first_index(queries, table):
    needle = f'"{table}"'
    for index, query in enumerate(queries):
        if needle in query["sql"]:
            return index
    return None


def _assert_counter_first(queries):
    counter = _first_index(queries, "sales_salescounter")
    assert counter is not None, "the writer never touched the sales counter"
    for table in ("sales_invoice", "sales_invoiceitem", "sales_payment", "sales_paymentallocation"):
        touched = _first_index(queries, table)
        assert touched is None or counter < touched, (
            f"{table} was read or written before the counter lock was taken"
        )


def test_void_invoice_takes_the_counter_lock_before_the_invoice_row():
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    with CaptureQueriesContext(connection) as captured:
        services.void_invoice(invoice, actor=admin, reason="Mistake")
    _assert_counter_first(captured.captured_queries)
    # and the invoice row lock is taken after it, in the same transaction
    assert _first_index(captured.captured_queries, "sales_invoice") is not None


def test_store_invoice_and_store_payment_take_the_counter_lock_first():
    world = build_world()
    with CaptureQueriesContext(connection) as captured:
        invoice = _invoice(world)
    _assert_counter_first(captured.captured_queries)
    with CaptureQueriesContext(connection) as captured:
        services.store_payment(
            worker=world.worker, device=world.device,
            data={"id": "33333333-3333-4333-8333-333333333333", "customer": world.customer,
                  "invoice": invoice, "amount_cents": 400, "method": "cash", "paid_at": utc(4)},
        )
    _assert_counter_first(captured.captured_queries)


def test_a_refused_void_does_not_leave_a_version_or_a_lock_behind():
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    services.void_invoice(invoice, actor=admin, reason="first")
    before = selectors.current_sales_cursor()
    with pytest.raises(ValueError):
        services.void_invoice(invoice, actor=admin, reason="second")
    assert selectors.current_sales_cursor() == before  # the counter bump rolled back


# ---- re-review 2: the void permit accepts only the real audit row ----------------------------

def _bad_audits(invoice, admin):
    other_action = AuditLog.objects.create(
        user=admin, action="invoice.note", entity="invoice", entity_id=str(invoice.pk))
    other_invoice = AuditLog.objects.create(
        user=admin, action="invoice.void", entity="invoice", entity_id=str(uuid.uuid4()))
    other_entity = AuditLog.objects.create(
        user=admin, action="invoice.void", entity="payment", entity_id=str(invoice.pk))
    unsaved = AuditLog(
        user=admin, action="invoice.void", entity="invoice", entity_id=str(invoice.pk))
    return {
        "namespace": SimpleNamespace(pk=uuid.uuid4()),
        "none": None,
        "other_action": other_action,
        "other_invoice": other_invoice,
        "other_entity": other_entity,
        "unsaved": unsaved,
    }


@pytest.mark.parametrize(
    "kind", ["namespace", "none", "other_action", "other_invoice", "other_entity", "unsaved"]
)
def test_void_permit_refuses_anything_but_the_real_audit_row(kind):
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    audit = _bad_audits(invoice, admin)[kind]
    with pytest.raises(RuntimeError):
        with void_permit(invoice.pk, audit):
            invoice._apply_void(actor=admin, reason="x", at=utc(4), sync_version=99)
    assert _status(invoice) == "active"


@pytest.mark.parametrize(
    "kind", ["namespace", "other_action", "other_invoice", "other_entity", "unsaved"]
)
def test_void_invoice_with_a_fake_audit_result_leaves_the_invoice_active(kind, monkeypatch):
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    fake = _bad_audits(invoice, admin)[kind]
    monkeypatch.setattr(services, "record_audit", lambda **kwargs: fake)
    with pytest.raises(RuntimeError):
        services.void_invoice(invoice, actor=admin, reason="Mistake")
    assert _status(invoice) == "active"
    assert invoice.status == "active"


def test_void_permit_accepts_the_real_row_and_only_for_that_invoice():
    world = build_world()
    invoice = _invoice(world)
    admin = make_user("boss", role="admin")
    audit = AuditLog.objects.create(
        user=admin, action="invoice.void", entity="invoice", entity_id=str(invoice.pk))
    with void_permit(invoice.pk, audit):
        invoice._apply_void(actor=admin, reason="ok", at=utc(4), sync_version=99)
    assert _status(invoice) == "void"
