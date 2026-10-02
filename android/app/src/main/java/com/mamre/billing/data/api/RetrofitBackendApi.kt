package com.mamre.billing.data.api

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/** Retrofit description of the contract. Paths are relative to the /api/v1/ base URL. */
interface MamreService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenPair

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AccessToken

    @GET("me")
    suspend fun me(@Header("Authorization") authorization: String): Me

    @GET("sync/catalog")
    suspend fun catalog(
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: Long,
    ): CatalogPull
}

class RetrofitBackendApi(
    private val service: MamreService,
    private val json: Json,
) : BackendApi {
    override suspend fun login(username: String, password: String) =
        call { service.login(LoginRequest(username, password)) }

    override suspend fun refresh(refreshToken: String) =
        call { service.refresh(RefreshRequest(refreshToken)) }.access

    override suspend fun me(accessToken: String) = call { service.me(bearer(accessToken)) }

    override suspend fun catalog(accessToken: String, cursor: Long) =
        call { service.catalog(bearer(accessToken), cursor) }

    private fun bearer(token: String) = "Bearer $token"

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw ApiErrorParser.parse(e.code(), e.response()?.errorBody()?.string(), json)
    }
}

/** Doc 2 s5.2: the app sends X-App-Version so a server can answer 426 to force an update. */
class AppVersionInterceptor(private val versionName: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-App-Version", versionName).build())
}
