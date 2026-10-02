"""Catalog tables (Doc 2 section 4.2): UUID pks, money in integer cents, sync_version cursor.

Rows are written only through services.py, which assigns sync_version (Doc 2 s4.1).
"""
import uuid

from django.db import models


class SyncCounter(models.Model):
    """Single row (pk=1) that hands out the monotonic sync_version values (DECISIONS.md)."""

    id = models.PositiveSmallIntegerField(primary_key=True, default=1)
    value = models.BigIntegerField(default=0)

    def __str__(self):
        return f"SyncCounter({self.value})"


class SyncedModel(models.Model):
    """Base for rows pulled by the device. Refuses a save that did not bump sync_version."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    sync_version = models.BigIntegerField(default=0, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        abstract = True

    def save(self, *args, **kwargs):
        saved = getattr(self, "_saved_version", None)
        if self.sync_version <= 0 or self.sync_version == saved:
            raise RuntimeError(
                f"{type(self).__name__} must be saved through catalog services (sync_version)."
            )
        super().save(*args, **kwargs)
        self._saved_version = self.sync_version

    @classmethod
    def from_db(cls, db, field_names, values):
        instance = super().from_db(db, field_names, values)
        instance._saved_version = instance.sync_version
        return instance


class PaymentMode(models.TextChoices):
    CASH = "cash", "Cash"
    CREDIT = "credit", "Credit"


class Product(SyncedModel):
    code = models.CharField(max_length=40, unique=True)
    name = models.CharField(max_length=120)
    units_per_packet = models.PositiveIntegerField()
    packing_cost_cents = models.BigIntegerField()  # cost field: never sent to workers
    yield_per_kg = models.DecimalField(max_digits=12, decimal_places=4)  # cost field
    is_active = models.BooleanField(default=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(units_per_packet__gt=0), name="product_units_gt_0"
            ),
            models.CheckConstraint(
                condition=models.Q(packing_cost_cents__gte=0), name="product_packing_gte_0"
            ),
            models.CheckConstraint(
                condition=models.Q(yield_per_kg__gt=0), name="product_yield_gt_0"
            ),
        ]

    def __str__(self):
        return self.name


class CustomerType(SyncedModel):
    name = models.CharField(max_length=80, unique=True)
    is_active = models.BooleanField(default=True)

    def __str__(self):
        return self.name


class Customer(SyncedModel):
    """Walk-in is not a row; it is a null customer (Doc 2 s4.2)."""

    name = models.CharField(max_length=150)
    type = models.ForeignKey(CustomerType, on_delete=models.PROTECT, related_name="customers")
    phone = models.CharField(max_length=40, blank=True)
    address = models.TextField(blank=True)
    payment_mode = models.CharField(max_length=10, choices=PaymentMode.choices)
    opening_balance_cents = models.BigIntegerField(default=0)
    notes = models.TextField(blank=True)
    is_active = models.BooleanField(default=True)

    def __str__(self):
        return self.name


class PriceDefault(SyncedModel):
    """History is kept and rows are never removed; the latest effective row wins."""

    product = models.ForeignKey(Product, on_delete=models.PROTECT, related_name="+")
    customer_type = models.ForeignKey(CustomerType, on_delete=models.PROTECT, related_name="+")
    unit_price_cents = models.BigIntegerField()
    effective_from = models.DateField()

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["customer_type", "product", "effective_from"],
                name="pricedefault_type_product_from_uniq",
            ),
            models.CheckConstraint(
                condition=models.Q(unit_price_cents__gt=0), name="pricedefault_price_gt_0"
            ),
        ]

    def __str__(self):
        return f"{self.customer_type_id}/{self.product_id} {self.effective_from}"


class PriceOverride(SyncedModel):
    """Beats PriceDefault for one customer and product. Admin only (Doc 1 s4.2)."""

    customer = models.ForeignKey(Customer, on_delete=models.PROTECT, related_name="+")
    product = models.ForeignKey(Product, on_delete=models.PROTECT, related_name="+")
    unit_price_cents = models.BigIntegerField()
    effective_from = models.DateField()
    note = models.TextField(blank=True)
    is_active = models.BooleanField(default=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["customer", "product", "effective_from"],
                name="priceoverride_customer_product_from_uniq",
            ),
            models.CheckConstraint(
                condition=models.Q(unit_price_cents__gt=0), name="priceoverride_price_gt_0"
            ),
        ]

    def __str__(self):
        return f"{self.customer_id}/{self.product_id} {self.effective_from}"
