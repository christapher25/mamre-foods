"""POST /api/v1/sync/push and GET /api/v1/sync/customer-activity (Doc 2 s5). Workers only."""
from rest_framework.exceptions import ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.permissions import IsWorker
from apps.sales import selectors as sales_selectors

from . import serializers, services


class PushView(APIView):
    permission_classes = [IsWorker]

    def post(self, request):
        return Response(services.push_batch(request.user, request.data))


def _cursor(params):
    raw = params.get("cursor")
    if raw in (None, ""):  # an empty cursor= counts as omitted, like /sync/catalog
        return 0
    if not str(raw).isdigit():
        raise ValidationError({"cursor": ["cursor must be a whole number, 0 or more."]})
    return int(raw)


class CustomerActivityView(APIView):
    permission_classes = [IsWorker]

    def get(self, request):
        cursor = _cursor(request.query_params)
        # The counter is read first. Rows at or below it belong to transactions that have
        # committed (the counter row is locked until commit), so nothing at or below it can
        # appear later; rows above it wait for the next pull (Doc 2 s6.4).
        ceiling = sales_selectors.current_sales_cursor()
        entries = sales_selectors.activity_since(cursor, ceiling)
        return Response(
            {
                "cursor": ceiling,
                "customers": [serializers.activity_customer(e) for e in entries],
            }
        )
