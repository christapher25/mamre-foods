package com.mamre.billing.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The sentence shown before the system permission prompt (A1 item 4). */
const val BLUETOOTH_RATIONALE = "Mamre needs permission to reach the built-in printer."

/**
 * Asks for BLUETOOTH_CONNECT at the first print that needs it (Android 12 and later). The screen that hosts the prompt sets
 * [requester]; it shows [BLUETOOTH_RATIONALE] first and then the system prompt. With no requester, or a "no", the answer is
 * false and the chain moves to the next driver.
 */
class BluetoothPermissionGate(private val granted: () -> Boolean) {
    @Volatile var requester: (suspend (rationale: String) -> Boolean)? = null

    fun isGranted(): Boolean = granted()

    suspend fun ensure(): Boolean = isGranted() || (requester?.invoke(BLUETOOTH_RATIONALE) == true && isGranted())

    companion object {
        fun forContext(context: Context) = BluetoothPermissionGate {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/** A1 item 2b: ESC/POS to the built-in printer over an already-paired Bluetooth serial link. */
@SuppressLint("MissingPermission")
class BluetoothEscPosDriver(
    private val context: Context,
    private val gate: BluetoothPermissionGate,
) : PrinterDriver {
    override val name = "bluetooth"

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun isAvailable(): Boolean = adapter()?.isEnabled == true

    override suspend fun print(doc: PrintDocument): PrintResult {
        if (!gate.ensure()) return PrintResult.Failed("Bluetooth permission denied")
        val adapter = adapter() ?: return PrintResult.Failed("No Bluetooth")
        val paired = adapter.bondedDevices.orEmpty().map { PairedDevice(it.address, it.name, it.bluetoothClass?.majorDeviceClass ?: 0) }
        val chosen = pickPrinter(paired) ?: return PrintResult.Failed("No paired printer")
        val bytes = EscPos.encode(doc.lines)
        return withContext(Dispatchers.IO) {
            try {
                val socket = adapter.getRemoteDevice(chosen.address).createRfcommSocketToServiceRecord(SERIAL_UUID)
                socket.use {
                    adapter.cancelDiscovery()
                    it.connect()
                    it.outputStream.write(bytes)
                    it.outputStream.flush()
                }
                PrintResult.Sent
            } catch (e: IOException) {
                PrintResult.Failed(e.message ?: "Bluetooth error")
            }
        }
    }

    companion object {
        val SERIAL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
