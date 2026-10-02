"""Root URL configuration (Doc 2 section 5). Base path /api/v1/."""
from django.urls import include, path

urlpatterns = [
    path("api/v1/", include("apps.accounts.urls")),
    path("api/v1/", include("apps.catalog.urls")),  # P1 only; A2 takes sync/ over
]
