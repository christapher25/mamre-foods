"""Login, refresh, me, deactivated users and role permissions.

Doc 2 s5 (auth endpoints), s5.2 (error body), s6.8 (lost device), s8 (roles, rate limit).
"""
from datetime import timedelta

import pytest
from django.conf import settings
from django.contrib.auth.models import AnonymousUser
from django.core.cache import cache
from django.urls import reverse
from django.utils import timezone
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.test import APIClient, APIRequestFactory
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.accounts.models import AuditLog, Device
from apps.accounts.permissions import IsAdmin, IsWorker
from tests.core_helpers import PASSWORD, make_user, token_client, worker_client

pytestmark = pytest.mark.django_db

LOGIN = "/api/v1/auth/login"
REFRESH = "/api/v1/auth/refresh"
ME = "/api/v1/me"


@pytest.fixture(autouse=True)
def _clear_throttle_cache():
    cache.clear()
    yield
    cache.clear()


def login(client, username="w1user", password=PASSWORD):
    return client.post(LOGIN, {"username": username, "password": password}, format="json")


def assert_error_body(response, code):
    body = response.json()
    assert set(body) == {"code", "message", "details"}
    assert body["code"] == code
    assert isinstance(body["details"], dict)


def test_user_has_uuid_pk_and_roles():
    user = make_user()
    assert len(str(user.pk)) == 36
    assert user.role == "worker"
    assert user.is_active is True


def test_login_returns_tokens_for_active_worker():
    make_user()
    response = login(APIClient())
    assert response.status_code == 200
    assert set(response.json()) == {"access", "refresh"}


def test_login_wrong_password_uses_error_body():
    make_user()
    response = login(APIClient(), password="nope")
    assert response.status_code == 401
    assert_error_body(response, "invalid_credentials")


def test_login_missing_fields_uses_error_body():
    response = APIClient().post(LOGIN, {}, format="json")
    assert response.status_code == 400
    assert_error_body(response, "validation_error")


def test_deactivated_user_cannot_login():
    make_user(is_active=False)
    response = login(APIClient())
    assert response.status_code == 401
    assert_error_body(response, "invalid_credentials")


def test_admin_cannot_get_tokens():
    make_user(username="boss", role="admin")
    response = login(APIClient(), username="boss")
    assert response.status_code == 401
    assert_error_body(response, "invalid_credentials")


def test_refresh_returns_new_access_token():
    make_user()
    tokens = login(APIClient()).json()
    response = APIClient().post(REFRESH, {"refresh": tokens["refresh"]}, format="json")
    assert response.status_code == 200
    assert "access" in response.json()


def test_refresh_with_garbage_token_is_401():
    response = APIClient().post(REFRESH, {"refresh": "garbage"}, format="json")
    assert response.status_code == 401
    assert_error_body(response, "invalid_credentials")


def test_deactivated_user_cannot_refresh():
    user = make_user()
    tokens = login(APIClient()).json()
    user.is_active = False
    user.save()
    response = APIClient().post(REFRESH, {"refresh": tokens["refresh"]}, format="json")
    assert response.status_code == 401
    assert_error_body(response, "invalid_credentials")


def test_deactivated_user_access_token_stops_working():
    user = make_user()
    client = worker_client(user)
    assert client.get(ME).status_code == 200
    user.is_active = False
    user.save()
    response = client.get(ME)
    assert response.status_code == 401
    assert_error_body(response, "not_authenticated")


def test_refresh_lifetime_about_30_days_and_access_is_short():
    assert settings.SIMPLE_JWT["REFRESH_TOKEN_LIFETIME"] == timedelta(days=30)
    assert settings.SIMPLE_JWT["ACCESS_TOKEN_LIFETIME"] <= timedelta(minutes=30)


def test_me_returns_profile_and_device_code():
    user = make_user()
    Device.objects.create(code="W1", name="Handheld", user=user, app_version="1.0")
    response = worker_client(user).get(ME)
    assert response.status_code == 200
    assert response.json() == {
        "id": str(user.pk),
        "username": "w1user",
        "full_name": "W1User",
        "role": "worker",
        "device_code": "W1",
    }


def test_me_device_code_null_without_device():
    assert worker_client().get(ME).json()["device_code"] is None


def test_me_returns_most_recent_device_code():
    user = make_user()
    old = Device.objects.create(code="W1", name="Old", user=user)
    Device.objects.create(code="W2", name="New", user=user)
    Device.objects.filter(pk=old.pk).update(created_at=timezone.now() - timedelta(days=1))
    assert worker_client(user).get(ME).json()["device_code"] == "W2"


def test_me_requires_authentication():
    response = APIClient().get(ME)
    assert response.status_code == 401
    assert_error_body(response, "not_authenticated")


def test_me_forbidden_for_admin_token():
    response = token_client(make_user(username="boss", role="admin")).get(ME)
    assert response.status_code == 403
    assert_error_body(response, "permission_denied")


class _Probe(APIView):
    authentication_classes: list = []
    permission_classes = [AllowAny]

    def get(self, request):
        return Response({"ok": True})


@pytest.mark.parametrize(
    ("role", "worker_ok", "admin_ok"),
    [("worker", True, False), ("admin", False, True)],
)
def test_role_permission_classes(role, worker_ok, admin_ok):
    request = APIRequestFactory().get("/x")
    request.user = make_user(username=f"{role}1", role=role)
    assert IsWorker().has_permission(request, _Probe()) is worker_ok
    assert IsAdmin().has_permission(request, _Probe()) is admin_ok


def test_role_permissions_reject_anonymous_and_inactive():
    request = APIRequestFactory().get("/x")
    request.user = AnonymousUser()
    assert not IsWorker().has_permission(request, _Probe())
    assert not IsAdmin().has_permission(request, _Probe())
    request.user = make_user(username="off", is_active=False)
    assert not IsWorker().has_permission(request, _Probe())


def test_login_is_rate_limited(monkeypatch):
    monkeypatch.setattr(ScopedRateThrottle, "THROTTLE_RATES", {"auth": "2/min"})
    make_user()
    client = APIClient()
    assert login(client, password="x").status_code == 401
    assert login(client, password="x").status_code == 401
    response = login(client, password="x")
    assert response.status_code == 429
    assert_error_body(response, "throttled")


def test_audit_log_is_append_only():
    entry = AuditLog.objects.create(action="test", entity="x", entity_id="1")
    entry.action = "changed"
    with pytest.raises(RuntimeError):
        entry.save()
    with pytest.raises(RuntimeError):
        entry.delete()
    with pytest.raises(RuntimeError):
        AuditLog.objects.all().delete()


def test_urls_are_routed_under_api_v1():
    assert reverse("auth-login") == LOGIN
    assert reverse("auth-refresh") == REFRESH
    assert reverse("me") == ME
