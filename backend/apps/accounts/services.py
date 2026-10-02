"""All writes for users, roles, devices.

Doc 2 section 3, cross-app rule: other apps call this module, never the tables.
"""
from django.contrib.auth import authenticate
from rest_framework_simplejwt.exceptions import TokenError
from rest_framework_simplejwt.tokens import RefreshToken

from . import selectors
from .exceptions import InvalidCredentials
from .models import AuditLog, Role


def record_audit(*, user, action, entity, entity_id, before=None, after=None):
    """Append one AuditLog row (Doc 2 s4.2, s8)."""
    return AuditLog.objects.create(
        user=user,
        action=action,
        entity=entity,
        entity_id=str(entity_id),
        before_json=before,
        after_json=after,
    )


def login_worker(username, password):
    """Doc 2 s5: tokens for an active worker only. Every failure is the same error so the
    API does not reveal whether a name exists, is an admin, or is deactivated."""
    user = authenticate(username=username, password=password)
    if user is None or not user.is_active or user.role != Role.WORKER:
        raise InvalidCredentials()
    refresh = RefreshToken.for_user(user)
    return {"access": str(refresh.access_token), "refresh": str(refresh)}


def refresh_access_token(refresh_token):
    """Doc 2 s5, s6.8: a new access token. A deactivated user cannot refresh."""
    try:
        refresh = RefreshToken(refresh_token)
    except TokenError:
        raise InvalidCredentials() from None
    if selectors.get_active_worker_by_id(refresh.get("user_id")) is None:
        raise InvalidCredentials()
    return {"access": str(refresh.access_token)}
