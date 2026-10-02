"""Mobile sync bookkeeping (Doc 2 s4.2 SyncBatchLog)."""
import uuid

from django.db import models


class SyncBatchLog(models.Model):
    """One row per push: which device, when, and how many records ended how (Doc 2 s4.2)."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    device = models.ForeignKey("accounts.Device", on_delete=models.PROTECT, related_name="+")
    received_at = models.DateTimeField()
    counts_json = models.JSONField()
    status = models.CharField(max_length=10)  # "ok" (nothing rejected) or "partial"

    def __str__(self):
        return f"{self.device_id} {self.received_at:%Y-%m-%d %H:%M} {self.status}"
