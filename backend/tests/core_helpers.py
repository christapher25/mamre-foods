"""Shared helpers for A1 (accounts and catalog) tests."""
from django.contrib.auth import get_user_model
from rest_framework.test import APIClient

PASSWORD = "correct-horse-battery"


def make_user(username="w1user", role="worker", is_active=True, **extra):
    User = get_user_model()
    return User.objects.create_user(
        username=username,
        password=PASSWORD,
        role=role,
        is_active=is_active,
        full_name=extra.pop("full_name", username.title()),
        **extra,
    )


def token_client(user):
    """An APIClient holding a valid access token for the given user."""
    from rest_framework_simplejwt.tokens import AccessToken

    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {AccessToken.for_user(user)}")
    return client


def worker_client(user=None):
    return token_client(user or make_user())
