package com.mamre.billing.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doc 2 s5.2: error body is {code, message, details}. */
class ApiErrorParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesCodeMessageAndDetails() {
        val e = ApiErrorParser.parse(
            401, """{"code":"invalid_credentials","message":"Wrong login.","details":{"field":"password"}}""", json,
        )
        assertEquals(401, e.status)
        assertEquals("invalid_credentials", e.code)
        assertEquals("Wrong login.", e.message)
        assertEquals("password", e.details.getValue("field").jsonPrimitive.content)
    }

    @Test fun missingDetailsBecomesEmpty() {
        val e = ApiErrorParser.parse(400, """{"code":"validation_error","message":"Bad."}""", json)
        assertEquals("validation_error", e.code)
        assertTrue(e.details.isEmpty())
    }

    @Test fun unknownExtraKeysAreIgnored() {
        val e = ApiErrorParser.parse(403, """{"code":"permission_denied","message":"No.","details":{},"x":1}""", json)
        assertEquals("permission_denied", e.code)
    }

    @Test fun nonJsonBodyKeepsStatusWithGenericCode() {
        val e = ApiErrorParser.parse(502, "<html>Bad gateway</html>", json)
        assertEquals(502, e.status)
        assertEquals("unknown_error", e.code)
    }

    @Test fun emptyOrNullBodyKeepsStatusWithGenericCode() {
        assertEquals("unknown_error", ApiErrorParser.parse(500, "", json).code)
        assertEquals("unknown_error", ApiErrorParser.parse(500, null, json).code)
    }

    @Test fun throttledAndUpdateRequiredCodesPassThrough() {
        assertEquals(429, ApiErrorParser.parse(429, """{"code":"throttled","message":"Slow down.","details":{}}""", json).status)
        assertEquals(426, ApiErrorParser.parse(426, """{"code":"upgrade_required","message":"Update.","details":{}}""", json).status)
    }
}
