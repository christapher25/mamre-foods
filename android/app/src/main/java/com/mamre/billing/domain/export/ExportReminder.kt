package com.mamre.billing.domain.export

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// The export reminder (Doc 1 s11.1, A-26). Version 1 has no automatic backup: the Export is the Owner's only protection, so the
// app shows when it was last prepared and nudges him when it has been a while. A constant, not a setting.

/** How many days may pass after an export before the Sales Home reminds the Owner. */
const val EXPORT_REMINDER_DAYS = 7L

/** Why an export is due now. */
sealed interface ExportDue {
    /** Bills exist and the data was never exported. */
    data object Never : ExportDue

    /** The last export is more than [EXPORT_REMINDER_DAYS] days old; [days] is whole days. */
    data class Days(val days: Long) : ExportDue

    /** The last export lies in the future: the phone's clock was moved back, so its age cannot be trusted. */
    data object ClockBehind : ExportDue
}

/**
 * The reminder rule, pure on an injected [clock]. Due when at least one bill exists AND the last export is missing, OR older than
 * [EXPORT_REMINDER_DAYS] days (exactly 7 days is not yet older), OR in the future. A brand-new app with no bills is never due.
 */
fun exportDue(lastExportAtMs: Long?, hasBills: Boolean, clock: Clock): ExportDue? {
    if (!hasBills) return null
    if (lastExportAtMs == null) return ExportDue.Never
    val now = clock.millis()
    if (lastExportAtMs > now) return ExportDue.ClockBehind
    val age = Duration.ofMillis(now - lastExportAtMs)
    return if (age > Duration.ofDays(EXPORT_REMINDER_DAYS)) ExportDue.Days(age.toDays()) else null
}

/** The settings value last_export_at as epoch milliseconds; a missing or damaged value counts as never exported. */
fun parseLastExport(stored: String?): Long? = stored?.trim()?.toLongOrNull()

fun exportReminderText(due: ExportDue): String = when (due) {
    ExportDue.Never -> "You have never exported your data. Export now to keep a copy."
    is ExportDue.Days -> "You have not exported your data for ${due.days} days. Export now to keep a copy."
    ExportDue.ClockBehind ->
        "The date of your last export is in the future, so this phone's clock may have been moved back. Export now to keep a copy."
}

/**
 * "Last export prepared: 19 Oct 2026, 3:10 PM" or "Last export prepared: never". "Prepared", not "done": the app cannot know
 * whether the Owner finished sharing. The date follows the phone's locale and time zone.
 */
fun lastExportLabel(lastExportAtMs: Long?, locale: Locale, zone: ZoneId): String {
    if (lastExportAtMs == null) return "Last export prepared: never"
    val text = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
        .format(Instant.ofEpochMilli(lastExportAtMs))
    return "Last export prepared: $text"
}
