package com.mamre.billing.data.repo

import com.mamre.billing.data.local.SettingDao
import com.mamre.billing.data.local.SettingEntity
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.domain.model.BusinessHeader
import com.mamre.billing.domain.worker.invoiceNumber
import com.mamre.billing.domain.worker.receiptNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings (Doc 2 s4.2): the business header, owner name, device code, wastage and the two number sequences. The header
 * comes only from here, never from code (Doc 1 A-31). [takeNextBillNumber] and [takeNextReceiptNumber] read and increase
 * the sequence, so they must be called inside the transaction that saves the bill or payment (Doc 2 I-3): a use case
 * that fails afterwards rolls the increase back and no number is burnt.
 */
class SettingsRepository(private val settings: SettingDao) {
    suspend fun get(key: String): String? = settings.get(key)

    suspend fun put(key: String, value: String) = settings.put(SettingEntity(key, value))

    suspend fun all(): Map<String, String> = settings.getAll().associate { it.key to it.value }

    fun observeAll(): Flow<Map<String, String>> = settings.observeAll().map { rows -> rows.associate { it.key to it.value } }

    suspend fun deviceCode(): String = settings.get(SettingKeys.DEVICE_CODE) ?: error("the first-run seed has not run: no device code")

    /** The name printed after Salesman on bills and shown as the person who made a change (Doc 1 A-29). May be empty. */
    suspend fun ownerName(): String = settings.get(SettingKeys.OWNER_NAME).orEmpty().trim()

    suspend fun wastageBp(): Int = settings.get(SettingKeys.WASTAGE_BP)?.toIntOrNull() ?: error("the first-run seed has not run: no wastage")

    suspend fun businessHeader(): BusinessHeader = businessHeaderOfSettings(all())

    suspend fun takeNextBillNumber(): String {
        val sequence = settings.get(SettingKeys.NEXT_BILL_SEQ)?.toIntOrNull() ?: 1
        settings.put(SettingEntity(SettingKeys.NEXT_BILL_SEQ, (sequence + 1).toString()))
        return invoiceNumber(deviceCode(), sequence)
    }

    suspend fun takeNextReceiptNumber(): String {
        val sequence = settings.get(SettingKeys.NEXT_RECEIPT_SEQ)?.toIntOrNull() ?: 1
        settings.put(SettingEntity(SettingKeys.NEXT_RECEIPT_SEQ, (sequence + 1).toString()))
        return receiptNumber(deviceCode(), sequence)
    }
}

/** The bill header from the settings; a missing key is an empty value, never a made-up one. */
fun businessHeaderOfSettings(settings: Map<String, String>): BusinessHeader = BusinessHeader(
    name = settings[SettingKeys.BUSINESS_NAME].orEmpty().trim(),
    addressLines = settings[SettingKeys.ADDRESS].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
    phone = settings[SettingKeys.PHONE].orEmpty().trim(),
    footer = settings[SettingKeys.FOOTER_TEXT].orEmpty().trim(),
)
