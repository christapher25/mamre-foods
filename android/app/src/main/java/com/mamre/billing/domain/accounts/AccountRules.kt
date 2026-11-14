package com.mamre.billing.domain.accounts

import com.mamre.billing.domain.model.normalizeSpaces

// Limits of an account (B8 items a and b, LEAD ruling L4). Pure, so the screens and the use cases share one rule.

const val MIN_USERNAME = 3
const val MAX_USERNAME = 20
const val MIN_DISPLAY_NAME = 2
const val MAX_DISPLAY_NAME = 40
const val MIN_PASSWORD = 6

private val USERNAME_CHARS = Regex("[a-z0-9._]+")

/** Trimmed and lower-cased (the stored form); 3 to 20 letters, digits, dot or underscore. Null when it does not fit. */
fun cleanUsername(raw: String): String? =
    raw.trim().lowercase().takeIf { it.length in MIN_USERNAME..MAX_USERNAME && USERNAME_CHARS.matches(it) }

/** The name printed on bills: 2 to 40 characters, inner spaces collapsed. Null when it does not fit. */
fun cleanDisplayName(raw: String): String? = normalizeSpaces(raw).takeIf { it.length in MIN_DISPLAY_NAME..MAX_DISPLAY_NAME }

/** At least 6 characters, any characters. Never trimmed: a space is part of a password. */
fun isValidPassword(password: String): Boolean = password.length >= MIN_PASSWORD
