from django.urls import path

from . import views

urlpatterns = [
    path("sync/catalog", views.CatalogSyncView.as_view(), name="sync-catalog"),
]
