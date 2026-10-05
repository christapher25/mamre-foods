package com.mamre.billing.domain.model

/** The order the four customer types are shown in everywhere (tiles, filter chips, reports): Restaurant, Shop, Retail, Catering. */
private val TYPE_DISPLAY_ORDER = listOf("Restaurant", "Shop", "Retail", "Catering")

/** Display order of customer types; a type that is not one of the four comes after them, by name. */
fun <T> List<T>.inTypeOrder(name: (T) -> String): List<T> =
    sortedWith(compareBy({ TYPE_DISPLAY_ORDER.indexOf(name(it)).let { i -> if (i < 0) TYPE_DISPLAY_ORDER.size else i } }, { name(it).lowercase() }))
