package com.mamre.billing.domain.usecase

/**
 * A business rule a use case refused (a missing reason, a price outside the limits, a customer that already exists...).
 * Nothing was written: every use case runs in one transaction. The message is meant to be shown to the Owner.
 */
open class RuleException(message: String) : Exception(message)

internal fun refuse(message: String): Nothing = throw RuleException(message)
