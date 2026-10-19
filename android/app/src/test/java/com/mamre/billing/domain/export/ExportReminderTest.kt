package com.mamre.billing.domain.export

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export reminder (Doc 1 s11.1, A-26): a pure rule on an injected Clock. Due = at least one bill AND (never exported, OR the
 * last export is older than 7 days, OR it is in the future because the phone's clock was moved back).
 */
class ExportReminderTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun ago(d: Duration) = now.minus(d).toEpochMilli()

    private fun due(last: Long?, hasBills: Boolean = true) = exportDue(last, hasBills, clock)

    @Test fun sevenDaysIsAConstantNotASetting() {
        assertEquals(7L, EXPORT_REMINDER_DAYS)
    }

    @Test fun neverExportedWithBillsIsDue() = assertEquals(ExportDue.Never, due(null))

    @Test fun sixDaysAgoIsNotDue() = assertNull(due(ago(Duration.ofDays(6))))

    @Test fun justNowIsNotDue() = assertNull(due(now.toEpochMilli()))

    @Test fun exactlySevenDaysIsNotYetDue() = assertNull(due(ago(Duration.ofDays(7))))

    @Test fun oneMillisecondPastSevenDaysIsDue() =
        assertEquals(ExportDue.Days(7), due(ago(Duration.ofDays(7)) - 1))

    @Test fun eightDaysAgoIsDueAndSaysEight() = assertEquals(ExportDue.Days(8), due(ago(Duration.ofDays(8))))

    @Test fun theDayCountIsWholeDaysRoundedDown() =
        assertEquals(ExportDue.Days(9), due(ago(Duration.ofDays(9).plusHours(23))))

    @Test fun aFutureExportTimeIsDueBecauseThePhoneClockWentBack() =
        assertEquals(ExportDue.ClockBehind, due(now.plus(Duration.ofDays(30)).toEpochMilli()))

    @Test fun aFutureExportTimeOneMillisecondAheadIsDueToo() =
        assertEquals(ExportDue.ClockBehind, due(now.toEpochMilli() + 1))

    @Test fun noBillsNeverShowsTheReminderWhateverTheExportTime() {
        assertNull(due(null, hasBills = false))
        assertNull(due(ago(Duration.ofDays(30)), hasBills = false))
        assertNull(due(now.plus(Duration.ofDays(30)).toEpochMilli(), hasBills = false))
    }

    @Test fun theRuleReadsTheInjectedClockNotTheSystemOne() {
        val last = ago(Duration.ofDays(6))
        assertNull(exportDue(last, true, clock))
        assertEquals(ExportDue.Days(7), exportDue(last, true, Clock.fixed(now.plus(Duration.ofDays(1)).plusSeconds(1), ZoneOffset.UTC)))
    }

    @Test fun theStoredValueIsReadAsEpochMillisAndAMissingOrDamagedOneCountsAsNever() {
        assertEquals(1_792_411_800_000L, parseLastExport("1792411800000"))
        assertNull(parseLastExport(null))
        assertNull(parseLastExport(""))
        assertNull(parseLastExport("yesterday"))
    }

    @Test fun theReminderTextsAreTheOwnersWords() {
        assertEquals(
            "You have not exported your data for 8 days. Export now to keep a copy.",
            exportReminderText(ExportDue.Days(8)),
        )
        assertEquals("You have never exported your data. Export now to keep a copy.", exportReminderText(ExportDue.Never))
        assertTrue(exportReminderText(ExportDue.ClockBehind).endsWith("Export now to keep a copy."))
    }

    @Test fun theLastExportLabelSaysNeverOrThePreparedTimeInThePhonesLocaleAndZone() {
        assertEquals("Last export prepared: never", lastExportLabel(null, Locale.UK, ZoneOffset.UTC))
        val at = Instant.parse("2026-10-19T15:10:00Z").toEpochMilli()
        assertEquals("Last export prepared: 19 Oct 2026, 15:10", lastExportLabel(at, Locale.UK, ZoneOffset.UTC))
        // The same moment in another zone reads differently (Kolkata is UTC+5:30).
        assertEquals("Last export prepared: 19 Oct 2026, 20:40", lastExportLabel(at, Locale.UK, ZoneId.of("Asia/Kolkata")))
        // The time zone can move the date too.
        assertEquals("Last export prepared: 20 Oct 2026, 01:10", lastExportLabel(at, Locale.UK, ZoneOffset.ofHours(10)))
    }
}
