"""Seeds ONLY the three customer types, the two products and business_name (owner, P1).

No prices (P-4 pending), no address, phone or footer (P-6 pending), no customers (P-8).
Product codes FRESH and CHAPATHI are placeholders (DECISIONS.md).
"""
from decimal import Decimal

from django.db import migrations

CUSTOMER_TYPES = ["Restaurant", "Shop", "Retail"]
PRODUCTS = [
    ("FRESH", "Mamre Fresh Chapathi"),
    ("CHAPATHI", "Mamre Chapathi"),
]


def seed(apps, schema_editor):
    SyncCounter = apps.get_model("catalog", "SyncCounter")
    CustomerType = apps.get_model("catalog", "CustomerType")
    Product = apps.get_model("catalog", "Product")
    AppSetting = apps.get_model("accounts", "AppSetting")

    counter, _ = SyncCounter.objects.get_or_create(pk=1)

    def bump():
        counter.value += 1
        counter.save()
        return counter.value

    for name in CUSTOMER_TYPES:
        CustomerType.objects.create(name=name, sync_version=bump())
    for code, name in PRODUCTS:
        Product.objects.create(
            code=code,
            name=name,
            units_per_packet=12,
            packing_cost_cents=15,
            yield_per_kg=Decimal("32"),
            sync_version=bump(),
        )
    AppSetting.objects.create(key="business_name", value="Mamre Foods", sync_version=bump())


class Migration(migrations.Migration):
    dependencies = [
        ("accounts", "0001_initial"),
        ("catalog", "0001_initial"),
    ]

    operations = [migrations.RunPython(seed, migrations.RunPython.noop)]
