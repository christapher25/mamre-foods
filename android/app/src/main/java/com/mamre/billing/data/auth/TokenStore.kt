package com.mamre.billing.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Where the JWTs live (Doc 2 s8: EncryptedSharedPreferences). */
interface TokenStore {
    val accessToken: String?
    val refreshToken: String?

    /** The role from /me, kept so a restart lands on the right home (Doc 2 s10). */
    val role: String?
    fun saveRole(role: String)

    /** Worker name and device code from /me, kept for offline use (Doc 2 s2, I-3). */
    val fullName: String?
    val deviceCode: String?
    fun saveProfile(fullName: String, deviceCode: String?)
    fun save(access: String, refresh: String)
    fun saveAccess(access: String)
    fun clear()
}

class EncryptedTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override val accessToken: String? get() = prefs.getString(ACCESS, null)
    override val refreshToken: String? get() = prefs.getString(REFRESH, null)
    override val role: String? get() = prefs.getString(ROLE, null)

    override fun saveRole(role: String) {
        prefs.edit().putString(ROLE, role).apply()
    }

    override val fullName: String? get() = prefs.getString(FULL_NAME, null)
    override val deviceCode: String? get() = prefs.getString(DEVICE_CODE, null)

    override fun saveProfile(fullName: String, deviceCode: String?) {
        prefs.edit().putString(FULL_NAME, fullName).putString(DEVICE_CODE, deviceCode).apply()
    }

    override fun save(access: String, refresh: String) {
        prefs.edit().putString(ACCESS, access).putString(REFRESH, refresh).apply()
    }

    override fun saveAccess(access: String) {
        prefs.edit().putString(ACCESS, access).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "mamre_tokens"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val ROLE = "role"
        const val FULL_NAME = "full_name"
        const val DEVICE_CODE = "device_code"
    }
}
