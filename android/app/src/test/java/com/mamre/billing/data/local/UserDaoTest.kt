package com.mamre.billing.data.local

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B8 item a: the users table. Accounts are never deleted; the username is unique as stored (lower case, L4). */
@RunWith(RobolectricTestRunner::class)
class UserDaoTest {
    private val db = TestDatabase.inMemory()

    @After fun tearDown() = db.close()

    private fun user(id: String, username: String, role: String = "SALESMAN") = UserEntity(
        id = id, username = username, displayName = "Name $id", role = role, passwordHash = "h", passwordSalt = "s",
        active = true, createdAt = 1L,
    )

    @Test fun anInsertedAccountIsReadBackByIdAndByUsername() = runBlocking {
        db.userDao().insert(user("u1", "asha"))
        assertEquals("u1", db.userDao().byUsername("asha")?.id)
        assertEquals("asha", db.userDao().get("u1")?.username)
        assertNull(db.userDao().byUsername("nobody"))
        assertEquals(1, db.userDao().count())
    }

    @Test fun twoAccountsCannotShareAUsername() = runBlocking {
        db.userDao().insert(user("u1", "asha"))
        try {
            db.userDao().insert(user("u2", "asha"))
            fail("a second 'asha' was stored")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(1, db.userDao().count())
    }

    @Test fun deactivatingKeepsTheRowAndSwitchingOnBringsItBack() = runBlocking {
        db.userDao().insert(user("u1", "asha"))
        db.userDao().setActive("u1", false)
        assertFalse(db.userDao().get("u1")!!.active)
        db.userDao().setActive("u1", true)
        assertTrue(db.userDao().get("u1")!!.active)
        assertEquals(1, db.userDao().count())
    }

    @Test fun theLockoutFieldsAndTheCredentialAreSavedPerAccount() = runBlocking {
        db.userDao().insert(user("u1", "asha"))
        db.userDao().insert(user("u2", "ravi"))
        db.userDao().saveLock("u1", failCount = 5, lockedUntil = 9L, delayMs = 30_000L, monoAt = 7L, bootId = "b")
        val a = db.userDao().get("u1")!!
        assertEquals(listOf(5, 9L, 30_000L, 7L, "b"), listOf(a.failCount, a.lockedUntil, a.lockDelayMs, a.lockMonoAt, a.lockBoot))
        assertEquals(0, db.userDao().get("u2")!!.failCount)
        db.userDao().setCredential("u2", "newhash", "newsalt")
        assertEquals("newhash", db.userDao().get("u2")!!.passwordHash)
        assertEquals("h", db.userDao().get("u1")!!.passwordHash)
    }

    @Test fun thereIsNoWayToDeleteAnAccount() {
        val methods = UserDao::class.java.methods.map { it.name.lowercase() }
        assertTrue(methods.none { it.contains("delete") || it.contains("remove") })
    }
}
