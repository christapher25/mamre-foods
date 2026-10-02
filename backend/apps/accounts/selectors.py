"""All reads for users, roles, devices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from .models import AppSetting, Device, Role, User


def get_active_worker_by_id(user_id):
    """The active worker with this id, or None."""
    return User.objects.filter(pk=user_id, is_active=True, role=Role.WORKER).first()


def is_active_admin(user):
    """True only if this user is, in the database right now, an active admin."""
    if user is None or getattr(user, "pk", None) is None:
        return False
    return User.objects.filter(pk=user.pk, is_active=True, role=Role.ADMIN).exists()


def current_device_code(user):
    """Code of the user's most recent device, or None (Doc 2 s5 GET /me)."""
    device = Device.objects.filter(user=user).order_by("-created_at").first()
    return device.code if device else None


def settings_changed_since(cursor, keys, ceiling):
    """AppSetting rows with cursor < sync_version <= ceiling, limited to the given keys."""
    return list(
        AppSetting.objects.filter(
            sync_version__gt=cursor, sync_version__lte=ceiling, key__in=keys
        ).order_by("sync_version")
    )
