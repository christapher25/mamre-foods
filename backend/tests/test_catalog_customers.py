"""Changing a customer's opening balance needs an admin and is audited.

Doc 1 s6.3 (balance), s4.1 (opening balance); Doc 2 s4.2 and s8 (AuditLog for balance
corrections); review item 6.
"""
import pytest

from apps.accounts.exceptions import AdminRequired
from apps.accounts.models import AuditLog
from apps.catalog import services
from apps.catalog.models import Customer, CustomerType
from tests.core_helpers import make_user

pytestmark = pytest.mark.django_db


@pytest.fixture
def admin():
    return make_user(username="boss", role="admin")


@pytest.fixture
def customer():
    return services.create_customer(
        name="Spice Garden", type=CustomerType.objects.get(name="Restaurant"),
        payment_mode="credit", opening_balance_cents=5000,
    )


@pytest.fixture(params=["none", "worker", "inactive_admin"])
def bad_actor(request):
    if request.param == "none":
        return None
    if request.param == "worker":
        return make_user(username="wk", role="worker")
    return make_user(username="gone", role="admin", is_active=False)


def test_admin_can_change_opening_balance_and_it_is_audited(admin, customer):
    services.update_customer(customer, actor=admin, opening_balance_cents=7500)
    customer.refresh_from_db()
    assert customer.opening_balance_cents == 7500
    entry = AuditLog.objects.get(entity="Customer")
    assert entry.action == "customer.opening_balance_update"
    assert entry.user == admin
    assert entry.entity_id == str(customer.pk)
    assert entry.before_json == {"opening_balance_cents": 5000}
    assert entry.after_json == {"opening_balance_cents": 7500}


def test_opening_balance_can_go_negative_for_credit_on_account(admin, customer):
    services.update_customer(customer, actor=admin, opening_balance_cents=-1200)
    customer.refresh_from_db()
    assert customer.opening_balance_cents == -1200


def test_opening_balance_change_without_an_admin_is_refused(bad_actor, customer):
    with pytest.raises(AdminRequired):
        services.update_customer(customer, actor=bad_actor, opening_balance_cents=1)
    customer.refresh_from_db()
    assert customer.opening_balance_cents == 5000
    assert AuditLog.objects.count() == 0


def test_opening_balance_change_without_any_actor_argument_is_refused(customer):
    with pytest.raises(AdminRequired):
        services.update_customer(customer, opening_balance_cents=1)
    assert Customer.objects.get(pk=customer.pk).opening_balance_cents == 5000


def test_refusal_rolls_back_other_fields_in_the_same_call(bad_actor, customer):
    with pytest.raises(AdminRequired):
        services.update_customer(
            customer, actor=bad_actor, phone="555-0100", opening_balance_cents=1
        )
    assert Customer.objects.get(pk=customer.pk).phone == ""


def test_other_fields_need_no_actor_and_write_no_audit(customer):
    services.update_customer(customer, phone="555-0100", notes="Net 30")
    assert Customer.objects.get(pk=customer.pk).phone == "555-0100"
    assert AuditLog.objects.count() == 0


def test_resending_the_same_opening_balance_is_not_a_change(customer):
    services.update_customer(customer, opening_balance_cents=5000, phone="555-0100")
    assert AuditLog.objects.count() == 0


def test_audit_and_data_change_commit_together_with_other_fields(admin, customer):
    services.update_customer(
        customer, actor=admin, opening_balance_cents=100, name="Spice Garden 2"
    )
    customer.refresh_from_db()
    assert (customer.name, customer.opening_balance_cents) == ("Spice Garden 2", 100)
    assert AuditLog.objects.filter(entity="Customer").count() == 1


def test_opening_balance_change_bumps_sync_version(admin, customer):
    before = customer.sync_version
    services.update_customer(customer, actor=admin, opening_balance_cents=1)
    assert customer.sync_version > before


@pytest.mark.parametrize("bad", [12.5, "100", True, None])
def test_opening_balance_must_be_integer_cents(admin, customer, bad):
    with pytest.raises(ValueError):
        services.update_customer(customer, actor=admin, opening_balance_cents=bad)
    assert Customer.objects.get(pk=customer.pk).opening_balance_cents == 5000
