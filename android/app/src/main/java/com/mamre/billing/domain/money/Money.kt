package com.mamre.billing.domain.money

// Money is Long cents everywhere (Doc 2 I-1, Doc 3 N1). No Double or Float is used to
// format or parse it. Cost per packet is a Long in ten-thousandths of a dollar (6975 is
// $0.6975, Doc 1 s9.5).

private const val CENTS_PER_DOLLAR = 100L
private const val TEN_THOUSANDTHS_PER_DOLLAR = 10_000L
private const val MAX_WHOLE_DIGITS = 9

/** 8100 -> "$81.00", -150 -> "-$1.50", 123456 -> "$1,234.56". Integer arithmetic only. */
fun formatCents(cents: Long): String = format(cents, CENTS_PER_DOLLAR, 2)

/** 8100 -> "81.00": an amount as plain text for an input field, no symbol or commas. */
fun centsToPlain(cents: Long): String = format(cents, CENTS_PER_DOLLAR, 2, symbol = "", group = false)

/** 6975 -> "$0.6975". Used for cost per packet, which needs four decimals. */
fun formatTenThousandths(value: Long): String = format(value, TEN_THOUSANDTHS_PER_DOLLAR, 4)

private fun format(value: Long, perDollar: Long, decimals: Int, symbol: String = "$", group: Boolean = true): String {
    // Divide first, then take absolute values, so Long.MIN_VALUE cannot overflow.
    val dollars = Math.abs(value / perDollar)
    val fraction = Math.abs(value % perDollar)
    val sign = if (value < 0) "-" else ""
    val whole = if (group) groupThousands(dollars) else dollars.toString()
    return sign + symbol + whole + "." + fraction.toString().padStart(decimals, '0')
}

private fun groupThousands(n: Long): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * Parses what a person types into cents without floating point: "12.50" -> 1250,
 * "12" -> 1200, "12.5" -> 1250, ".5" -> 50, "-3" -> -300. Returns null for anything else:
 * blank, letters, a second dot, more than two decimals, or an absurdly large number.
 */
fun parseCents(text: String): Long? {
    val s = text.trim()
    val negative = s.startsWith("-")
    val body = if (negative) s.substring(1) else s
    val parts = body.split(".")
    if (parts.size > 2) return null
    val whole = parts[0]
    val frac = parts.getOrElse(1) { "" }
    if (whole.isEmpty() && frac.isEmpty()) return null
    if (!whole.all { it in '0'..'9' } || !frac.all { it in '0'..'9' }) return null
    if (whole.length > MAX_WHOLE_DIGITS || frac.length > 2) return null
    val cents = (whole.ifEmpty { "0" }.toLong() * CENTS_PER_DOLLAR) + frac.padEnd(2, '0').toLong()
    return if (negative) -cents else cents
}
