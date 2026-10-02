"""GET /api/v1/sync/catalog (Doc 2 s5).

Routed from the catalog app for P1 only, by owner authorisation (DECISIONS.md); A2 owns sync/.
"""
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.permissions import IsWorker

from . import selectors
from .serializers import (
    CursorSerializer,
    WorkerCustomerSerializer,
    WorkerCustomerTypeSerializer,
    WorkerPriceDefaultSerializer,
    WorkerPriceOverrideSerializer,
    WorkerProductSerializer,
    WorkerSettingSerializer,
)

SERIALIZERS = {
    "products": WorkerProductSerializer,
    "customer_types": WorkerCustomerTypeSerializer,
    "customers": WorkerCustomerSerializer,
    "price_defaults": WorkerPriceDefaultSerializer,
    "price_overrides": WorkerPriceOverrideSerializer,
    "settings": WorkerSettingSerializer,
}


class CatalogSyncView(APIView):
    permission_classes = [IsWorker]

    def get(self, request):
        params = CursorSerializer(data=request.query_params)
        params.is_valid(raise_exception=True)
        changes = selectors.changes_since(params.validated_data["cursor"])
        body = {"cursor": changes["cursor"]}
        for name, serializer in SERIALIZERS.items():
            body[name] = serializer(changes[name], many=True).data
        return Response(body)
