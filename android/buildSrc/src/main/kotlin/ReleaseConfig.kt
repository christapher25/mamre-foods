package com.mamre.build

import java.util.Properties

/**
 * Release signing and version rules (C1, Doc 4 F1). Plain Kotlin so the Gradle build and the unit tests run the same code.
 * Nothing here ever prints a password.
 */
object ReleaseConfig {
    data class Version(val code: Int, val name: String)

    class Keystore(val storeFile: String, val storePassword: String, val keyAlias: String, val keyPassword: String) {
        override fun toString() = "Keystore(storeFile=$storeFile, keyAlias=$keyAlias)"
    }

    private fun props(text: String) = Properties().apply { load(text.reader()) }

    fun parseVersion(text: String): Version {
        val p = props(text)
        val code = p.getProperty("versionCode")?.trim()?.toIntOrNull()
        check(code != null && code > 0) { "version.properties: versionCode must be a whole number above 0" }
        val name = p.getProperty("versionName")?.trim()
        check(!name.isNullOrEmpty()) { "version.properties: versionName is missing" }
        return Version(code, name)
    }

    /** null text = no keystore.properties = the release stays unsigned (CI). */
    fun parseKeystore(text: String?): Keystore? {
        if (text == null) return null
        val p = props(text)
        fun need(key: String) = p.getProperty(key)?.trim().takeUnless { it.isNullOrEmpty() } ?: error("keystore.properties: $key is missing")
        return Keystore(need("storeFile"), need("storePassword"), need("keyAlias"), need("keyPassword"))
    }

    /** certs = the signer certificate subjects of the release APK (apksigner --print-certs). */
    fun signingProblems(certs: List<String>): List<String> {
        if (certs.isEmpty()) return listOf("the release APK is unsigned")
        return certs.filter { it.contains("Android Debug", ignoreCase = true) }.map { "the release APK is signed with the debug key: $it" }
    }
}
