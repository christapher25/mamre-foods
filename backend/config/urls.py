"""Root URL configuration (Doc 2 section 5). Base path /api/v1/."""
from django.urls import include, path

urlpatterns = [
    path("api/v1/", include("apps.accounts.urls")),
]
