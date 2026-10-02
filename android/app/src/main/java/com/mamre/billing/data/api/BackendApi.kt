package com.mamre.billing.data.api

/**
 * The mobile API of docs/api/openapi.yaml v0.2.0 (Doc 2 s5). Server refusals throw
 * [ApiException]; no signal throws java.io.IOException. Authenticated calls take the access
 * token so token handling lives in one place (SessionManager).
 */
interface BackendApi {
    /** POST /auth/login */
    suspend fun login(username: String, password: String): TokenPair

    /** POST /auth/refresh: returns a new access token. Refresh tokens are not rotated. */
    suspend fun refresh(refreshToken: String): String

    /** GET /me */
    suspend fun me(accessToken: String): Me

    /** GET /sync/catalog?cursor=N */
    suspend fun catalog(accessToken: String, cursor: Long): CatalogPull
}
