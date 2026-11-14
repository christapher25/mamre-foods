package com.mamre.billing.domain.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8 items a and b, L4: username and display name limits, password length. */
class AccountRulesTest {
    @Test fun aUsernameIsTrimmedAndStoredInLowerCase() {
        assertEquals("asha.k_2", cleanUsername("  Asha.K_2 "))
    }

    @Test fun aUsernameIsThreeToTwentyOfLettersDigitsDotUnderscore() {
        assertNull(cleanUsername("ab"))
        assertEquals("abc", cleanUsername("abc"))
        assertEquals("a".repeat(20), cleanUsername("a".repeat(20)))
        assertNull(cleanUsername("a".repeat(21)))
        assertNull(cleanUsername("two words"))
        assertNull(cleanUsername("bad-dash"))
        assertNull(cleanUsername("naïve"))
        assertNull(cleanUsername(""))
    }

    @Test fun aDisplayNameIsTwoToFortyWithInnerSpacesCollapsed() {
        assertEquals("Asha Kumar", cleanDisplayName("  Asha    Kumar "))
        assertNull(cleanDisplayName("A"))
        assertNull(cleanDisplayName("x".repeat(41)))
        assertEquals("x".repeat(40), cleanDisplayName("x".repeat(40)))
    }

    @Test fun aPasswordIsAtLeastSixCharactersOfAnyKind() {
        assertFalse(isValidPassword("12345"))
        assertTrue(isValidPassword("123456"))
        assertTrue(isValidPassword("pass word!"))
        assertFalse(isValidPassword(""))
    }
}
