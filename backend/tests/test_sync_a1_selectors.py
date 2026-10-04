"""Read-only selectors added for A2 (owner-authorised, DECISIONS.md). Doc 2 s3 cross-app rule."""
import uuid

import pytest

from apps.accounts import selectors as accounts_selectors
from apps.accounts.models import Device
from apps.catalog import selectors as catalog_selectors
from apps.catalog.models import Customer, CustomerType, Product, SyncCounter
from apps.catalog.services import next_sync_version
from tests.core_helpers import make_user

pytestmark = pytest.mark.django_db


def test_get_device_only_for_the_owner():
    w1, w2 = make_user("a"), make_user("b")
    Device.objects.create(code="W1", user=w1)
    assert accounts_selectors.get_device(w1, "W1").code == "W1"
    assert accounts_selectors.get_device(w2, "W1") is None
    assert accounts_selectors.get_device(w1, "W9") is None
    assert accounts_selectors.get_device(w1, "") is None
    assert accounts_selectors.get_device(None, "W1") is None


def _customer(name, mode):
    ctype = CustomerType.objects.first()
    from apps.catalog.services import create_customer

    return create_customer(name=name, type=ctype, payment_mode=mode)


def test_get_customer_and_product():
    c = _customer("Spice", "credit")
    assert catalog_selectors.get_customer(c.id).pk == c.pk
    assert catalog_selectors.get_customer(uuid.uuid4()) is None
    assert catalog_selectors.get_customer("not-a-uuid") is None
    product = Product.objects.first()
    assert catalog_selectors.get_product(str(product.id)).pk == product.pk
    assert catalog_selectors.get_product(uuid.uuid4()) is None
    assert catalog_selectors.get_product("nope") is None


def test_credit_customers_lists_only_credit():
    credit = _customer("Credit Co", "credit")
    _customer("Cash Co", "cash")
    assert [c.pk for c in catalog_selectors.credit_customers() if c.name != ""] == [credit.pk]
    assert all(isinstance(c, Customer) for c in catalog_selectors.credit_customers())


def test_current_cursor_follows_the_counter():
    before = catalog_selectors.current_cursor()
    version = next_sync_version()
    assert catalog_selectors.current_cursor() == version == before + 1
    SyncCounter.objects.all().delete()
    assert catalog_selectors.current_cursor() == 0
