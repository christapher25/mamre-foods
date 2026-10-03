package com.mamre.billing.domain.admin

// Change set D1: the rules for adding a salesman (UI text says "salesman"; the role stays "worker" in code and API).
// Pure, so they are tested without a screen. The server checks them again (Doc 2 s8).

const val SALESMAN_NAME_MIN = 2
const val SALESMAN_NAME_MAX = 40
const val LOGIN_NAME_MIN = 3
const val LOGIN_NAME_MAX = 20

enum class SalesmanProblem {
    NAME_TOO_SHORT, NAME_TOO_LONG, NAME_BAD_CHARACTERS, NAME_DUPLICATE,
    LOGIN_REQUIRED, LOGIN_TOO_SHORT, LOGIN_TOO_LONG, LOGIN_BAD_CHARACTERS, LOGIN_DUPLICATE,
}

sealed interface SalesmanNameCheck {
    /** [name] is trimmed with inside runs of spaces made one. */
    data class Ok(val name: String) : SalesmanNameCheck

    data class Invalid(val problem: SalesmanProblem) : SalesmanNameCheck
}

sealed interface SalesmanCheck {
    data class Ok(val name: String, val login: String) : SalesmanCheck

    data class Invalid(val problem: SalesmanProblem) : SalesmanCheck
}

private val SPACES = Regex("\\s+")

/** Trimmed, with every run of spaces made one, so "Rajesh  Thomas" and "Rajesh Thomas" are one name. */
fun canonicalName(raw: String): String = raw.trim().replace(SPACES, " ")

/** A name is 2 to 40 characters of letters, spaces and dots, and not used already (ignoring case and extra spaces). */
fun validateSalesmanName(raw: String, existingNames: Collection<String>): SalesmanNameCheck {
    val name = canonicalName(raw)
    val problem = when {
        name.length < SALESMAN_NAME_MIN -> SalesmanProblem.NAME_TOO_SHORT
        name.length > SALESMAN_NAME_MAX -> SalesmanProblem.NAME_TOO_LONG
        !name.all { it.isLetter() || it == ' ' || it == '.' } -> SalesmanProblem.NAME_BAD_CHARACTERS
        existingNames.any { canonicalName(it).equals(name, ignoreCase = true) } -> SalesmanProblem.NAME_DUPLICATE
        else -> null
    }
    return if (problem == null) SalesmanNameCheck.Ok(name) else SalesmanNameCheck.Invalid(problem)
}

/** Name first, then the login name: 3 to 20 letters and digits, unique ignoring case. */
fun validateNewSalesman(
    rawName: String,
    rawLogin: String,
    existingNames: Collection<String>,
    existingLogins: Collection<String>,
): SalesmanCheck {
    val name = when (val n = validateSalesmanName(rawName, existingNames)) {
        is SalesmanNameCheck.Invalid -> return SalesmanCheck.Invalid(n.problem)
        is SalesmanNameCheck.Ok -> n.name
    }
    val login = rawLogin.trim()
    val problem = when {
        login.isEmpty() -> SalesmanProblem.LOGIN_REQUIRED
        login.length < LOGIN_NAME_MIN -> SalesmanProblem.LOGIN_TOO_SHORT
        login.length > LOGIN_NAME_MAX -> SalesmanProblem.LOGIN_TOO_LONG
        !login.all { it.isLetterOrDigit() } -> SalesmanProblem.LOGIN_BAD_CHARACTERS
        existingLogins.any { it.trim().equals(login, ignoreCase = true) } -> SalesmanProblem.LOGIN_DUPLICATE
        else -> null
    }
    return if (problem == null) SalesmanCheck.Ok(name, login) else SalesmanCheck.Invalid(problem)
}

fun salesmanProblemMessage(problem: SalesmanProblem): String = when (problem) {
    SalesmanProblem.NAME_TOO_SHORT -> "The name needs at least $SALESMAN_NAME_MIN characters"
    SalesmanProblem.NAME_TOO_LONG -> "The name can have at most $SALESMAN_NAME_MAX characters"
    SalesmanProblem.NAME_BAD_CHARACTERS -> "Use letters, spaces and dots only"
    SalesmanProblem.NAME_DUPLICATE -> "A salesman with this name already exists. Names must differ, even by capital letters."
    SalesmanProblem.LOGIN_REQUIRED -> "Enter a login name"
    SalesmanProblem.LOGIN_TOO_SHORT -> "The login name needs at least $LOGIN_NAME_MIN characters"
    SalesmanProblem.LOGIN_TOO_LONG -> "The login name can have at most $LOGIN_NAME_MAX characters"
    SalesmanProblem.LOGIN_BAD_CHARACTERS -> "The login name can have letters and digits only"
    SalesmanProblem.LOGIN_DUPLICATE -> "This login name is already used. Choose another."
}
