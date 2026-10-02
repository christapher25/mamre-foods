from django.urls import path

from . import views

urlpatterns = [
    path("sync/push", views.PushView.as_view(), name="sync-push"),
    path(
        "sync/customer-activity",
        views.CustomerActivityView.as_view(),
        name="sync-customer-activity",
    ),
]
