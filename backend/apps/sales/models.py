"""Invoices, items, payments and allocations (Doc 2 s4.2, s4.3).

UUID primary keys, money in integer cents (I-1). These rows are append-only (I-4, I-9): the
models refuse update, delete, bulk update and bulk delete. The one change allowed is an invoice
going active to void, through services.void_invoice (Doc 1 s5.4).
"""
import uuid

from django.conf import settings
from django.db import models

IMMUTABLE_MESSAGE = "{name} rows are append-only: never edited or deleted (Doc 2 I-4, I-9)."


class ImmutableQuerySet(models.QuerySet):
    def update(self, **kwargs):
        raise RuntimeError(IMMUTABLE_MESSAGE.format(name=self.model.__name__))

    def bulk_update(self, objs, fields, *args, **kwargs):
        raise RuntimeError(IMMUTABLE_MESSAGE.format(name=self.model.__name__))

    def delete(self):
        raise RuntimeError(IMMUTABLE_MESSAGE.format(name=self.model.__name__))


class SalesCounter(models.Model):
    """Single row (pk=1) handing out the monotonic sync_version values for sales rows.
    Same pattern as the catalog counter (Doc 2 s6.4); see services.next_sales_version."""

    id = models.PositiveSmallIntegerField(primary_key=True, default=1)
    value = models.BigIntegerField(default=0)

    def __str__(self):
        return f"SalesCounter({self.value})"


class ImmutableModel(models.Model):
    """Base for append-only rows. An insert needs a sync_version taken from the counter."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    sync_version = models.BigIntegerField(db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

    objects = ImmutableQuerySet.as_manager()

    class Meta:
        abstract = True

    def save(self, *args, **kwargs):
        if not self._state.adding:
            raise RuntimeError(IMMUTABLE_MESSAGE.format(name=type(self).__name__))
        if not self.sync_version or self.sync_version <= 0:
            raise RuntimeError("sync_version must come from services.next_sales_version().")
        super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise RuntimeError(IMMUTABLE_MESSAGE.format(name=type(self).__name__))


class InvoiceStatus(models.TextChoices):
    ACTIVE = "active", "Active"
    VOID = "void", "Void"


class PaymentMethod(models.TextChoices):
    CASH = "cash", "Cash"
    ZELLE = "zelle", "Zelle"
    CHECK = "check", "Check"
    CARD = "card", "Card"
    OTHER = "other", "Other"


class Invoice(ImmutableModel):
    """Doc 2 s4.2. customer is null for a walk-in. Names are snapshots (Doc 1 s5.3)."""

    number = models.CharField(max_length=40, unique=True)  # I-3, unique in the database
    customer = models.ForeignKey(
        "catalog.Customer", null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
    customer_name = models.CharField(max_length=150, blank=True)
    customer_type = models.CharField(max_length=80, blank=True)
    worker = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="+")
    device = models.ForeignKey("accounts.Device", on_delete=models.PROTECT, related_name="+")
    issued_at = models.DateTimeField()
    total_cents = models.BigIntegerField()
    status = models.CharField(
        max_length=10, choices=InvoiceStatus.choices, default=InvoiceStatus.ACTIVE
    )
    void_reason = models.TextField(blank=True)
    voided_by = models.ForeignKey(
        settings.AUTH_USER_MODEL, null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
    voided_at = models.DateTimeField(null=True, blank=True)
    client_created_at = models.DateTimeField()  # the device time, as sent (Doc 2 s4.1, s6.7)
    server_received_at = models.DateTimeField()
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(total_cents__gt=0), name="invoice_total_gt_0"
            ),
            models.CheckConstraint(
                condition=models.Q(status="active") | ~models.Q(void_reason=""),
                name="invoice_void_has_reason",
            ),
        ]
        indexes = [models.Index(fields=["customer", "issued_at"])]

    def __str__(self):
        return self.number

    def apply_void(self, *, actor, reason, at, sync_version):
        """The ONLY update an invoice ever gets: active to void (Doc 1 s5.4, I-4).
        Called by services.void_invoice after it checked the actor and the reason."""
        if self.status != InvoiceStatus.ACTIVE:
            raise ValueError("Only an active invoice can be voided.")
        self.status = InvoiceStatus.VOID
        self.void_reason = reason
        self.voided_by = actor
        self.voided_at = at
        self.sync_version = sync_version
        # Bypass ImmutableModel.save on purpose, and write only the void columns.
        models.Model.save(
            self,
            update_fields=[
                "status", "void_reason", "voided_by", "voided_at", "sync_version", "updated_at",
            ],
        )


class InvoiceItem(ImmutableModel):
    """The price is the device snapshot, stored as sent (Doc 1 s4.3, I-7)."""

    invoice = models.ForeignKey(Invoice, on_delete=models.PROTECT, related_name="items")
    product = models.ForeignKey("catalog.Product", on_delete=models.PROTECT, related_name="+")
    product_name = models.CharField(max_length=120)
    qty_packets = models.PositiveIntegerField()
    unit_price_cents = models.BigIntegerField()
    line_total_cents = models.BigIntegerField()

    class Meta:
        constraints = [
            models.CheckConstraint(condition=models.Q(qty_packets__gt=0), name="item_qty_gt_0"),
            models.CheckConstraint(
                condition=models.Q(unit_price_cents__gt=0), name="item_price_gt_0"
            ),
            models.CheckConstraint(
                condition=models.Q(line_total_cents__gt=0), name="item_line_gt_0"
            ),
        ]

    def __str__(self):
        return f"{self.invoice_id} {self.product_name} x{self.qty_packets}"


class Payment(ImmutableModel):
    """Append-only. customer is null for a walk-in payment (never allocated, Doc 1 s4.1).
    The Payment.status column of the Doc 2 table is not used (DECISIONS.md)."""

    receipt_number = models.CharField(max_length=40, blank=True, default="")  # "" = none
    customer = models.ForeignKey(
        "catalog.Customer", null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
    invoice = models.ForeignKey(
        Invoice, null=True, blank=True, on_delete=models.PROTECT, related_name="payments"
    )
    amount_cents = models.BigIntegerField()
    method = models.CharField(max_length=10, choices=PaymentMethod.choices)
    paid_at = models.DateTimeField()
    worker = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="+")
    device = models.ForeignKey("accounts.Device", on_delete=models.PROTECT, related_name="+")
    note = models.TextField(blank=True)
    client_created_at = models.DateTimeField()
    server_received_at = models.DateTimeField()

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(amount_cents__gt=0), name="payment_amount_gt_0"
            ),
            models.CheckConstraint(
                condition=models.Q(customer__isnull=False) | models.Q(invoice__isnull=False),
                name="payment_has_customer_or_invoice",
            ),
        ]
        indexes = [models.Index(fields=["customer", "paid_at"])]

    def __str__(self):
        return f"{self.receipt_number or self.pk} {self.amount_cents}"


class PaymentAllocation(ImmutableModel):
    """Created by the server only, oldest invoice first (Doc 1 s6.2, I-5)."""

    payment = models.ForeignKey(Payment, on_delete=models.PROTECT, related_name="allocations")
    invoice = models.ForeignKey(Invoice, on_delete=models.PROTECT, related_name="allocations")
    amount_cents = models.BigIntegerField()

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(amount_cents__gt=0), name="alloc_amount_gt_0"
            ),
        ]

    def __str__(self):
        return f"{self.payment_id} -> {self.invoice_id} {self.amount_cents}"
