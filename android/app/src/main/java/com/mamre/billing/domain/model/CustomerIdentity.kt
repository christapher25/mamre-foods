package com.mamre.billing.domain.model

// Change set D2: a customer is a name plus a location (an area or a branch). The pair is unique, ignoring case and
// extra spaces. Retail may have no location, unless the name is already taken. Pure functions, shared by the Admin
// form, the server stand-in and the salesman screens.

const val RETAIL_TYPE_NAME = "Retail"

private val SPACES = Regex("\\s+")

/** Trimmed, with every run of spaces made one. */
fun normalizeSpaces(raw: String): String = raw.trim().replace(SPACES, " ")

private fun key(raw: String) = normalizeSpaces(raw).lowercase()

/** What the salesman and the Admin read: "Name - Location", or just the name when there is no location. */
fun customerLabel(name: String, location: String): String {
    val n = normalizeSpaces(name)
    val l = normalizeSpaces(location)
    return if (l.isEmpty()) n else "$n - $l"
}

/** The name and location of one existing customer. [id] lets an edit skip the customer itself. */
data class CustomerIdentity(val id: String, val name: String, val location: String)

enum class IdentityProblem { NAME_REQUIRED, LOCATION_REQUIRED, LOCATION_REQUIRED_NAME_EXISTS, DUPLICATE }

/**
 * Null when the name and location are fine. [others] are the other customers (leave the edited one out).
 * - a name is required;
 * - with no location: a name that already exists needs one, and so does every type except Retail;
 * - with a location: the same name and location as another customer is a duplicate.
 */
fun checkCustomerIdentity(
    name: String,
    location: String,
    typeName: String,
    others: List<CustomerIdentity>,
): IdentityProblem? {
    val n = key(name)
    val l = key(location)
    if (n.isEmpty()) return IdentityProblem.NAME_REQUIRED
    val nameExists = others.any { key(it.name) == n }
    if (l.isEmpty()) {
        return when {
            nameExists -> IdentityProblem.LOCATION_REQUIRED_NAME_EXISTS
            !typeName.trim().equals(RETAIL_TYPE_NAME, ignoreCase = true) -> IdentityProblem.LOCATION_REQUIRED
            else -> null
        }
    }
    return if (others.any { key(it.name) == n && key(it.location) == l }) IdentityProblem.DUPLICATE else null
}

fun identityProblemMessage(problem: IdentityProblem): String = when (problem) {
    IdentityProblem.NAME_REQUIRED -> "Enter the customer's name"
    IdentityProblem.LOCATION_REQUIRED -> "Enter a location (area or branch). Only Retail customers can have none."
    IdentityProblem.LOCATION_REQUIRED_NAME_EXISTS ->
        "A customer with this name already exists. Enter a location (area or branch) to tell them apart."
    IdentityProblem.DUPLICATE -> "A customer with this name and location already exists."
}

/** Search: every word typed must appear in the name or the location, ignoring case. An empty search matches all. */
fun customerMatches(name: String, location: String, query: String): Boolean {
    val words = key(query).split(" ").filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val label = key(customerLabel(name, location))
    return words.all { label.contains(it) }
}

/** "Name - Location" of a catalog customer. */
val Customer.label: String get() = customerLabel(name, location)
