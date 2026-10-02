"""Error body {"code", "message", "details"} for every API error (Doc 2 s5.2)."""
from django.http import Http404
from rest_framework import exceptions, status
from rest_framework.views import exception_handler as drf_exception_handler


class InvalidCredentials(exceptions.APIException):
    status_code = status.HTTP_401_UNAUTHORIZED
    default_detail = "Invalid username or password."
    default_code = "invalid_credentials"


class AdminRequired(PermissionError):
    """A write that only an active Admin may make (Doc 1 s2) was attempted by someone else."""


def _code_for(exc):
    if isinstance(exc, InvalidCredentials):
        return "invalid_credentials"
    if isinstance(exc, exceptions.ValidationError):
        return "validation_error"
    if isinstance(exc, (exceptions.NotAuthenticated, exceptions.AuthenticationFailed)):
        return "not_authenticated"
    if isinstance(exc, exceptions.PermissionDenied):
        return "permission_denied"
    if isinstance(exc, (exceptions.NotFound, Http404)):
        return "not_found"
    if isinstance(exc, exceptions.Throttled):
        return "throttled"
    return getattr(exc, "default_code", "error")


def api_exception_handler(exc, context):
    response = drf_exception_handler(exc, context)
    if response is None:
        return None
    data = response.data
    if isinstance(exc, exceptions.ValidationError):
        message = "Invalid input."
        details = data if isinstance(data, dict) else {"errors": data}
    else:
        message = str(data.get("detail", exc)) if isinstance(data, dict) else str(exc)
        details = {}
        if isinstance(exc, exceptions.Throttled) and exc.wait is not None:
            details = {"retry_after_seconds": int(exc.wait)}
    response.data = {"code": _code_for(exc), "message": message, "details": details}
    return response
