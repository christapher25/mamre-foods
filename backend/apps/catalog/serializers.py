"""Worker-facing catalog serializers (Doc 2 s8, I-8).

Every field is listed by name. Never use fields="__all__" or exclude=. Cost fields
(packing_cost_cents, yield_per_kg), opening_balance_cents and notes are deliberately absent.
"""
from rest_framework import serializers

from .models import Customer, CustomerType, PriceDefault, PriceOverride, Product


class WorkerProductSerializer(serializers.ModelSerializer):
    class Meta:
        model = Product
        fields = ("id", "code", "name", "units_per_packet", "is_active")


class WorkerCustomerTypeSerializer(serializers.ModelSerializer):
    class Meta:
        model = CustomerType
        fields = ("id", "name", "is_active")


class WorkerCustomerSerializer(serializers.ModelSerializer):
    type_id = serializers.UUIDField(read_only=True)

    class Meta:
        model = Customer
        fields = ("id", "name", "type_id", "phone", "address", "payment_mode", "is_active")


class WorkerPriceDefaultSerializer(serializers.ModelSerializer):
    product_id = serializers.UUIDField(read_only=True)
    customer_type_id = serializers.UUIDField(read_only=True)

    class Meta:
        model = PriceDefault
        fields = ("id", "product_id", "customer_type_id", "unit_price_cents", "effective_from")


class WorkerPriceOverrideSerializer(serializers.ModelSerializer):
    customer_id = serializers.UUIDField(read_only=True)
    product_id = serializers.UUIDField(read_only=True)

    class Meta:
        model = PriceOverride
        fields = (
            "id", "customer_id", "product_id", "unit_price_cents", "effective_from", "is_active",
        )


class WorkerSettingSerializer(serializers.Serializer):
    key = serializers.CharField()
    value = serializers.CharField(allow_blank=True)


class CursorSerializer(serializers.Serializer):
    cursor = serializers.IntegerField(min_value=0, required=False, default=0)
