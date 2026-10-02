"""Role permissions, enforced server-side on every endpoint (Doc 2 s8, Doc 1 s2)."""
from rest_framework.permissions import BasePermission

from .models import Role


class _HasRole(BasePermission):
    role: str

    def has_permission(self, request, view):
        user = request.user
        return bool(
            user and user.is_authenticated and user.is_active and user.role == self.role
        )


class IsWorker(_HasRole):
    role = Role.WORKER


class IsAdmin(_HasRole):
    role = Role.ADMIN
