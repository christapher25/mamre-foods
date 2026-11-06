package com.mamre.billing.print

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1 item 4: the permission gate, with a fake requester. A "no" sends the chain on to the next driver. */
class BluetoothPermissionGateTest {
    @Test fun grantedNeverAsks() = runTest {
        val gate = BluetoothPermissionGate { true }
        gate.requester = { error("must not ask") }
        assertTrue(gate.ensure())
    }

    @Test fun notGrantedAsksWithTheExactSentenceAndAYesGrants() = runTest {
        var granted = false
        val asked = mutableListOf<String>()
        val gate = BluetoothPermissionGate { granted }
        gate.requester = { sentence -> asked += sentence; granted = true; true }
        assertTrue(gate.ensure())
        assertEquals(listOf("Mamre needs permission to reach the built-in printer."), asked)
    }

    @Test fun deniedIsFalseSoTheChainMovesOn() = runTest {
        val gate = BluetoothPermissionGate { false }
        gate.requester = { false }
        assertFalse(gate.ensure())
    }

    @Test fun noRequesterMeansDenied() = runTest {
        assertFalse(BluetoothPermissionGate { false }.ensure())
    }

    @Test fun aDeniedBluetoothDriverFailsAndTheChainUsesTheNextDriver() = runTest {
        val gate = BluetoothPermissionGate { false }
        val denied = object : PrinterDriver {
            override val name = "bluetooth"
            override fun isAvailable() = true
            override suspend fun print(doc: PrintDocument) =
                if (gate.ensure()) PrintResult.Sent else PrintResult.Failed("Bluetooth permission denied")
        }
        val next = object : PrinterDriver {
            override val name = "android"
            override fun isAvailable() = true
            override suspend fun print(doc: PrintDocument): PrintResult = PrintResult.Sent
        }
        val store = object : PrinterMethodStore {
            var v: String? = null
            override suspend fun saved() = v
            override suspend fun save(name: String) { v = name }
        }
        assertEquals(PrintResult.Sent, PrinterChain(listOf(denied, next), store).print(PrintDocument(listOf("x"))))
        assertEquals("android", store.v)
    }
}
