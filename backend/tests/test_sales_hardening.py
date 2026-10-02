"""Reviewer findings for the sales app (Doc 2 I-3, I-4, I-9, s6.4)."""
import pytest

from apps.sales.models import Invoice, InvoiceItem, Payment, PaymentAllocation

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
