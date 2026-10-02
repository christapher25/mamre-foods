from rest_framework import serializers


class LoginSerializer(serializers.Serializer):
    username = serializers.CharField()
    password = serializers.CharField(trim_whitespace=False)


class RefreshSerializer(serializers.Serializer):
    refresh = serializers.CharField()


class MeSerializer(serializers.Serializer):
    """Worker-facing profile. Explicit fields only (Doc 2 s8)."""

    id = serializers.UUIDField()
    username = serializers.CharField()
    full_name = serializers.CharField()
    role = serializers.CharField()
    device_code = serializers.CharField(allow_null=True)
