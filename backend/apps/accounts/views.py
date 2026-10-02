"""Auth endpoints under /api/v1/ (Doc 2 s5)."""
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from . import selectors, services
from .permissions import IsWorker
from .serializers import LoginSerializer, MeSerializer, RefreshSerializer


class _AuthView(APIView):
    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth"  # Doc 2 s8: login is rate limited


class LoginView(_AuthView):
    def post(self, request):
        data = LoginSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        return Response(services.login_worker(**data.validated_data))


class RefreshView(_AuthView):
    def post(self, request):
        data = RefreshSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        return Response(services.refresh_access_token(data.validated_data["refresh"]))


class MeView(APIView):
    permission_classes = [IsWorker]

    def get(self, request):
        user = request.user
        profile = {
            "id": user.pk,
            "username": user.username,
            "full_name": user.full_name,
            "role": user.role,
            "device_code": selectors.current_device_code(user),
        }
        return Response(MeSerializer(profile).data)
