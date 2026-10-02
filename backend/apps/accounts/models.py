"""Users, devices, settings and the audit log (Doc 2 section 4.2)."""
import uuid

from django.contrib.auth.models import AbstractUser, UserManager
from django.db import models


class Role(models.TextChoices):
    ADMIN = "admin", "Admin"
    WORKER = "worker", "Worker"


class AccountsUserManager(UserManager):
    def create_superuser(self, username, email=None, password=None, **extra_fields):
        # A superuser is an Admin (Doc 1 section 2).
        extra_fields.setdefault("role", Role.ADMIN)
        return super().create_superuser(username, email, password, **extra_fields)


class User(AbstractUser):
    """Doc 2 s4.2: id, username, full_name, role, is_active. Least privilege by default."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    full_name = models.CharField(max_length=150, blank=True)
    role = models.CharField(max_length=10, choices=Role.choices, default=Role.WORKER)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    objects = AccountsUserManager()

    def __str__(self):
        return self.username


class Device(models.Model):
    """Doc 2 s4.2: one code per device (W1); the code appears in invoice numbers."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    code = models.CharField(max_length=20, unique=True)
    name = models.CharField(max_length=100, blank=True)
    user = models.ForeignKey(User, on_delete=models.PROTECT, related_name="devices")
    last_sync_at = models.DateTimeField(null=True, blank=True)
    app_version = models.CharField(max_length=40, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.code


class AppSetting(models.Model):
    """Doc 2 s4.2 system AppSetting: key, value. sync_version is set by catalog services
    so business header settings reach the device (DECISIONS.md, P1)."""

    key = models.CharField(max_length=100, primary_key=True)
    value = models.TextField(blank=True)
    sync_version = models.BigIntegerField(default=0, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.key


class _AppendOnlyQuerySet(models.QuerySet):
    def update(self, **kwargs):
        raise RuntimeError("AuditLog is append-only.")

    def delete(self):
        raise RuntimeError("AuditLog is append-only.")


class AuditLog(models.Model):
    """Doc 2 s4.2 and s8: price changes, voids, locks, balance corrections. Append-only."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(
        User, null=True, blank=True, on_delete=models.PROTECT, related_name="+"
    )
    action = models.CharField(max_length=60)
    entity = models.CharField(max_length=60)
    entity_id = models.CharField(max_length=64)
    before_json = models.JSONField(null=True, blank=True)
    after_json = models.JSONField(null=True, blank=True)
    at = models.DateTimeField(auto_now_add=True)

    objects = _AppendOnlyQuerySet.as_manager()

    def __str__(self):
        return f"{self.action} {self.entity} {self.entity_id}"

    def save(self, *args, **kwargs):
        if not self._state.adding:
            raise RuntimeError("AuditLog is append-only.")
        super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise RuntimeError("AuditLog is append-only.")
