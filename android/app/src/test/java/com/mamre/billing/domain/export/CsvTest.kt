package com.mamre.billing.domain.export

import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 4180 (Doc 2 s5.3): a field with a comma, a quote or a line break is quoted, quotes are doubled, records end in CRLF. */
class CsvTest {
    @Test fun aPlainRecordIsCommaSeparatedWithACrlfEnd() {
        assertEquals("a,b,c\r\n", csvRecord(listOf("a", "b", "c")))
    }

    @Test fun commasQuotesAndLineBreaksForceQuotingAndQuotesAreDoubled() {
        assertEquals("\"Mario's \"\"Pizza\"\", Plano\"\r\n", csvRecord(listOf("Mario's \"Pizza\", Plano")))
        assertEquals("\"a\nb\"\r\n", csvRecord(listOf("a\nb")))
        assertEquals("\"a\r\nb\"\r\n", csvRecord(listOf("a\r\nb")))
        assertEquals("\"a\rb\"\r\n", csvRecord(listOf("a\rb")))
    }

    @Test fun anEmptyFieldAndUnicodeAreWrittenAsTheyAre() {
        assertEquals(",é中,\r\n", csvRecord(listOf("", "é中", "")))
    }

    @Test fun aSpaceOnItsOwnNeedsNoQuotesButASingleQuoteCharacterIsPlain() {
        assertEquals(" ,it's\r\n", csvRecord(listOf(" ", "it's")))
    }

    @Test fun aDocumentIsTheHeaderThenOneRecordPerRowAndHeadersOnlyWhenThereAreNoRows() {
        assertEquals("id,name\r\n1,A\r\n2,\"B,C\"\r\n", csvDocument(listOf("id", "name"), listOf(listOf("1", "A"), listOf("2", "B,C"))))
        assertEquals("id,name\r\n", csvDocument(listOf("id", "name"), emptyList()))
    }
}
