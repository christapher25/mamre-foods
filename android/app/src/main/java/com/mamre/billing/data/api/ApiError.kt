package com.mamre.billing.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A server error answer: HTTP [status] plus the Doc 2 s5.2 body {code, message, details}.
 * Network failures are NOT this type; they stay as IOException so callers can tell
 * "offline" from "refused".
 */
class ApiException(
    val status: Int,
    val code: String,
    message: String,
    val details: JsonObject = JsonObject(emptyMap()),
) : Exception(message)

object ApiErrorParser {
    const val UNKNOWN_CODE = "unknown_error"

    fun parse(status: Int, body: String?, json: Json): ApiException {
        if (!body.isNullOrBlank()) {
            try {
                val parsed = json.decodeFromString(ApiErrorBody.serializer(), body)
                return ApiException(status, parsed.code, parsed.message, parsed.details)
            } catch (_: Exception) {
                // Not our error shape (proxy page, server crash): fall through.
            }
        }
        return ApiException(status, UNKNOWN_CODE, "Server error ($status)")
    }
}
