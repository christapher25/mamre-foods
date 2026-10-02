"""All reads for users, roles, devices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from .models import Device, Role, User


def get_active_worker_by_id(user_id):
    """The active worker with this id, or None."""
    return User.objects.filter(pk=user_id, is_active=True, role=Role.WORKER).first()


def current_device_code(user):
    """Code of the user's most recent device, or None (Doc 2 s5 GET /me)."""
    device = Device.objects.filter(user=user).order_by("-created_at").first()
    return device.code if device else None
