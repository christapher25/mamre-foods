package com.mamre.billing.domain.admin

import com.mamre.billing.domain.money.formatMilli
import com.mamre.billing.domain.money.parseCents
import com.mamre.billing.domain.money.parseMilli
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Rules for the materials list, purchases, recipes, wastage and production damage (B5, B6, B7).
// Pure functions, tested without a screen. Quantities are Long thousandths of the base unit ("mb").

private const val MILLI = 1000L

// --- add purchase (B6, add only) ---

enum class PurchaseProblem {
    MATERIAL_REQUIRED, DATE_REQUIRED, UNIT_REQUIRED,
    QUANTITY_INVALID, QUANTITY_NOT_POSITIVE, TOTAL_INVALID, TOTAL_NOT_POSITIVE,
}

sealed interface PurchaseCheck {
    /** [qtyMb] is in thousandths of the material's base unit. */
    data class Ok(val materialId: String, val date: LocalDate, val qtyMb: Long, val totalCents: Long) : PurchaseCheck

    data class Invalid(val problems: Set<PurchaseProblem>) : PurchaseCheck
}

/**
 * A purchase needs a material, a date, a quantity above zero in a chosen unit and the total paid above
 * zero (owner brief, B6). The quantity becomes thousandths of the base unit with integer arithmetic:
 * 400 kg of wheat is 400,000,000.
 */
fun validatePurchase(
    materialId: String?,
    date: LocalDate?,
    quantityText: String,
    unit: MaterialUnit?,
    totalText: String,
): PurchaseCheck {
    val problems = mutableSetOf<PurchaseProblem>()
    if (materialId.isNullOrBlank()) problems += PurchaseProblem.MATERIAL_REQUIRED
    if (date == null) problems += PurchaseProblem.DATE_REQUIRED
    if (unit == null) problems += PurchaseProblem.UNIT_REQUIRED
    val milli = parseMilli(quantityText)
    when {
        milli == null -> problems += PurchaseProblem.QUANTITY_INVALID
        milli <= 0 -> problems += PurchaseProblem.QUANTITY_NOT_POSITIVE
    }
    val cents = parseCents(totalText)
    when {
        cents == null -> problems += PurchaseProblem.TOTAL_INVALID
        cents <= 0 -> problems += PurchaseProblem.TOTAL_NOT_POSITIVE
    }
    if (problems.isNotEmpty()) return PurchaseCheck.Invalid(problems)
    return PurchaseCheck.Ok(materialId!!, date!!, milli!! * unit!!.mbPerUnit / MILLI, cents!!)
}

fun purchaseProblemMessage(problem: PurchaseProblem): String = when (problem) {
    PurchaseProblem.MATERIAL_REQUIRED -> "Choose the material"
    PurchaseProblem.DATE_REQUIRED -> "Choose the purchase date"
    PurchaseProblem.UNIT_REQUIRED -> "Choose the unit"
    PurchaseProblem.QUANTITY_INVALID -> "Enter a quantity like 400 or 12.5"
    PurchaseProblem.QUANTITY_NOT_POSITIVE -> "The quantity must be more than zero"
    PurchaseProblem.TOTAL_INVALID -> "Enter the total paid like 440.00"
    PurchaseProblem.TOTAL_NOT_POSITIVE -> "The total paid must be more than $0.00"
}

private const val MAX_BAGS = 1_000_000L

/**
 * The bags helper: whole bags times the amount per bag, as quantity text ("16" x "25" is "400").
 * Null when either is not usable, so the quantity field is left alone.
 */
fun bagsTimes(bagsText: String, perBagText: String): String? {
    val bags = bagsText.trim().toLongOrNull()?.takeIf { it in 1..MAX_BAGS } ?: return null
    val perBag = parseMilli(perBagText)?.takeIf { it > 0 } ?: return null
    return formatMilli(bags * perBag)
}

// --- recipe quantity (B5) ---

enum class RecipeProblem { INVALID, NOT_POSITIVE }

sealed interface RecipeCheck {
    data class Ok(val qtyMb: Long) : RecipeCheck

    data class Invalid(val problem: RecipeProblem) : RecipeCheck
}

/** A recipe quantity per packet is in the base unit (g, ml or pieces), above zero, with up to three decimals. */
fun validateRecipeQuantity(text: String): RecipeCheck {
    val milli = parseMilli(text) ?: return RecipeCheck.Invalid(RecipeProblem.INVALID)
    return if (milli <= 0) RecipeCheck.Invalid(RecipeProblem.NOT_POSITIVE) else RecipeCheck.Ok(milli)
}

fun recipeProblemMessage(problem: RecipeProblem): String = when (problem) {
    RecipeProblem.INVALID -> "Enter a quantity like 375 or 5.625"
    RecipeProblem.NOT_POSITIVE -> "The quantity must be more than zero"
}

// --- wastage 0 to 5% (B9 Settings, B5 Costing) ---

const val MAX_WASTAGE_BP = 500
const val DEFAULT_WASTAGE_BP = 200

enum class WastageProblem { INVALID, OUT_OF_RANGE }

sealed interface WastageCheck {
    data class Ok(val basisPoints: Int) : WastageCheck

    data class Invalid(val problem: WastageProblem) : WastageCheck
}

/** Wastage is a percent from 0 to 5 with up to two decimals, kept as basis points (2% is 200). */
fun validateWastage(text: String): WastageCheck {
    val bp = parseCents(text) ?: return WastageCheck.Invalid(WastageProblem.INVALID)
    return if (bp < 0 || bp > MAX_WASTAGE_BP) {
        WastageCheck.Invalid(WastageProblem.OUT_OF_RANGE)
    } else {
        WastageCheck.Ok(bp.toInt())
    }
}

fun wastageProblemMessage(problem: WastageProblem): String = when (problem) {
    WastageProblem.INVALID -> "Enter a percent like 2 or 2.5"
    WastageProblem.OUT_OF_RANGE -> "Wastage must be between 0% and 5%"
}

/** 250 -> "2.5", 200 -> "2": basis points as percent text. */
fun formatWastage(basisPoints: Int): String {
    val whole = basisPoints / 100
    val frac = basisPoints % 100
    if (frac == 0) return "$whole"
    return "$whole." + frac.toString().padStart(2, '0').trimEnd('0')
}

// --- production damage (B7, add only) ---

enum class DamageProblem { PRODUCT_REQUIRED, DATE_REQUIRED, PACKETS_INVALID, PACKETS_NOT_POSITIVE }

sealed interface DamageCheck {
    data class Ok(val productId: String, val date: LocalDate, val packets: Int) : DamageCheck

    data class Invalid(val problems: Set<DamageProblem>) : DamageCheck
}

fun validateProductionDamage(productId: String?, date: LocalDate?, packetsText: String): DamageCheck {
    val problems = mutableSetOf<DamageProblem>()
    if (productId.isNullOrBlank()) problems += DamageProblem.PRODUCT_REQUIRED
    if (date == null) problems += DamageProblem.DATE_REQUIRED
    val packets = packetsText.trim().toLongOrNull()
    when {
        packets == null || packets > Int.MAX_VALUE -> problems += DamageProblem.PACKETS_INVALID
        packets <= 0 -> problems += DamageProblem.PACKETS_NOT_POSITIVE
    }
    return if (problems.isEmpty()) DamageCheck.Ok(productId!!, date!!, packets!!.toInt()) else DamageCheck.Invalid(problems)
}

fun damageProblemMessage(problem: DamageProblem): String = when (problem) {
    DamageProblem.PRODUCT_REQUIRED -> "Choose the product"
    DamageProblem.DATE_REQUIRED -> "Choose the date"
    DamageProblem.PACKETS_INVALID -> "Enter a whole number of packets"
    DamageProblem.PACKETS_NOT_POSITIVE -> "Packets must be more than zero"
}

// --- showing quantities ---

/** A stock quantity in the material's purchase unit: 17,500,000 thousandths of wheat is "17.5 kg". */
fun formatQuantity(qtyMb: Long, material: Material): String {
    val per = material.purchaseMbPerUnit
    val sign = if (qtyMb < 0) -1L else 1L
    val milli = sign * ((Math.abs(qtyMb) * MILLI + per / 2) / per) // thousandths of the purchase unit, half up
    return "${formatMilli(milli)} ${material.purchaseUnit}"
}

/** A recipe quantity in the base unit: 5,625 is "5.625 g". */
fun formatRecipeQuantity(qtyMb: Long, baseUnit: String): String = "${formatMilli(qtyMb)} $baseUnit"

// --- date picker (the Material 3 DatePicker works in UTC milliseconds) ---

fun dateToPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun pickerMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
