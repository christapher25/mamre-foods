"""The sync_version guard covers every pulled model, including AppSetting.

Doc 2 s4.1 (monotonic sync_version), s6.4; owner answer 7 and review items 2 and 3.
The guard lives in apps.accounts.sync_guard so accounts and catalog share it without a cycle.
"""
import pytest

from apps.accounts import services as accounts_services
from apps.accounts.models import AppSetting
from apps.catalog.models import Customer, CustomerType, PriceDefault, PriceOverride, Product

pytestmark = pytest.mark.django_db

GUARDED = [Product, CustomerType, Customer, PriceDefault, PriceOverride, AppSetting]
IDS = [m.__name__ for m in GUARDED]


@pytest.mark.parametrize("model", GUARDED, ids=IDS)
def test_queryset_update_is_refused(model):
    with pytest.raises(RuntimeError):
        model.objects.all().update(sync_version=99)


@pytest.mark.parametrize("model", GUARDED, ids=IDS)
def test_manager_update_is_refused(model):
    with pytest.raises(RuntimeError):
        model.objects.update(sync_version=99)


@pytest.mark.parametrize("model", GUARDED, ids=IDS)
def test_bulk_create_is_refused(model):
    with pytest.raises(RuntimeError):
        model.objects.bulk_create([model()])


@pytest.mark.parametrize("model", GUARDED, ids=IDS)
def test_bulk_update_is_refused(model):
    with pytest.raises(RuntimeError):
        model.objects.bulk_update([], ["sync_version"])


@pytest.mark.parametrize("model", GUARDED, ids=IDS)
def test_get_or_create_cannot_create_an_unversioned_row(model):
    with pytest.raises(RuntimeError):
        if model is AppSetting:
            model.objects.get_or_create(key="never-existed")
        else:
            model.objects.create()


def test_guarded_refusals_leave_data_untouched():
    before = list(Product.objects.values_list("name", "sync_version"))
    with pytest.raises(RuntimeError):
        Product.objects.all().update(name="Hacked")
    assert list(Product.objects.values_list("name", "sync_version")) == before


# --- AppSetting save guard ---------------------------------------------------------------------


def test_appsetting_create_without_a_bump_is_refused():
    with pytest.raises(RuntimeError):
        AppSetting(key="phone", value="555").save()
    assert not AppSetting.objects.filter(key="phone").exists()


def test_appsetting_create_with_a_stale_zero_version_is_refused():
    with pytest.raises(RuntimeError):
        AppSetting(key="phone", value="555", sync_version=0).save()


def test_appsetting_save_without_a_bump_is_refused():
    setting = AppSetting.objects.get(key="business_name")
    setting.value = "Sneaky"
    with pytest.raises(RuntimeError):
        setting.save()
    assert AppSetting.objects.get(key="business_name").value == "Mamre Foods"


def test_set_setting_bumps_on_create_and_on_change():
    first = accounts_services.set_setting("phone", "555-0100")
    second = accounts_services.set_setting("phone", "555-0199")
    assert 0 < first.sync_version < second.sync_version
    assert AppSetting.objects.get(key="phone").value == "555-0199"
    assert AppSetting.objects.get(key="phone").sync_version == second.sync_version


def test_a_saved_setting_cannot_be_saved_again_without_a_new_bump():
    setting = accounts_services.set_setting("phone", "555-0100")
    setting.value = "other"
    with pytest.raises(RuntimeError):
        setting.save()


def test_set_setting_works_for_a_row_loaded_from_the_database():
    accounts_services.set_setting("phone", "1")
    again = accounts_services.set_setting("phone", "2")
    assert again.value == "2"
