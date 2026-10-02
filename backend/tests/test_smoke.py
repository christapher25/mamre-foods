"""P0 smoke test (Doc 3 section 4, P0 gate): every empty app imports cleanly."""
import importlib

import pytest
from django.apps import apps

APP_NAMES = [
    "accounts",
    "catalog",
    "sales",
    "sync",
    "production",
    "expenses",
    "costing",
    "reports",
    "dashboard",
]

# Doc 2 section 3 cross-app rule: services.py holds writes, selectors.py holds reads.
APP_MODULES = ["", ".services", ".selectors"]


@pytest.mark.parametrize("name", APP_NAMES)
@pytest.mark.parametrize("suffix", APP_MODULES)
def test_app_modules_import(name, suffix):
    importlib.import_module(f"apps.{name}{suffix}")


@pytest.mark.parametrize("name", APP_NAMES)
def test_app_is_installed(name):
    assert apps.is_installed(f"apps.{name}")
