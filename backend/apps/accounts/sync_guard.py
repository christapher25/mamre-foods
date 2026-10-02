"""Shared sync_version guard for every row the device pulls (Doc 2 s4.1, s6.4).

It lives in accounts, the lowest layer, so accounts.AppSetting and the catalog models share
one guard without an import cycle (catalog already depends on accounts, never the reverse).
The counter itself and the services that bump it stay in catalog (DECISIONS.md).

The guard refuses every write that would skip the bump: save() without a new sync_version,
QuerySet.update, bulk_create and bulk_update.
"""
from django.db import models

MESSAGE = "{name} must be written through catalog services so sync_version is bumped."


class SyncGuardQuerySet(models.QuerySet):
    def update(self, **kwargs):
        raise RuntimeError(MESSAGE.format(name=self.model.__name__))

    def bulk_create(self, objs, *args, **kwargs):
        raise RuntimeError(MESSAGE.format(name=self.model.__name__))

    def bulk_update(self, objs, fields, *args, **kwargs):
        raise RuntimeError(MESSAGE.format(name=self.model.__name__))


class SyncGuarded(models.Model):
    """Abstract base: a monotonic sync_version and a save() that refuses an unbumped write."""

    sync_version = models.BigIntegerField(default=0, db_index=True)

    objects = SyncGuardQuerySet.as_manager()

    class Meta:
        abstract = True

    def save(self, *args, **kwargs):
        saved = getattr(self, "_saved_version", None)
        if self.sync_version <= 0 or self.sync_version == saved:
            raise RuntimeError(MESSAGE.format(name=type(self).__name__))
        super().save(*args, **kwargs)
        self._saved_version = self.sync_version

    @classmethod
    def from_db(cls, db, field_names, values):
        instance = super().from_db(db, field_names, values)
        instance._saved_version = instance.sync_version
        return instance
