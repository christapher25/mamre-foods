package com.mamre.billing.print

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import woyou.aidlservice.jiuiv5.ICallback
import woyou.aidlservice.jiuiv5.IWoyouService

private const val BIND_TIMEOUT_MS = 4_000L
private const val PRINT_TIMEOUT_MS = 10_000L

/**
 * A1 item 2a: a built-in printer reached through its maker's own service. This base binds the service, hands the receipt as
 * ESC/POS bytes and waits for the answer. A3 adds another vendor as one more small subclass.
 */
abstract class VendorServiceDriver(private val context: Context) : PrinterDriver {
    protected abstract val servicePackage: String
    protected abstract val serviceAction: String

    protected abstract suspend fun sendBytes(binder: IBinder, bytes: ByteArray): PrintResult

    override fun isAvailable(): Boolean =
        try {
            context.packageManager.getPackageInfo(servicePackage, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    override suspend fun print(doc: PrintDocument): PrintResult {
        val bound = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) bound.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(serviceAction).setPackage(servicePackage)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return PrintResult.Failed("Printer service not found")
        return try {
            val binder = withTimeout(BIND_TIMEOUT_MS) { bound.await() }
            sendBytes(binder, EscPos.encode(doc.lines))
        } catch (e: TimeoutCancellationException) {
            PrintResult.Failed("Printer service did not answer")
        } catch (e: android.os.RemoteException) {
            PrintResult.Failed(e.message ?: "Printer service error")
        } finally {
            context.unbindService(connection)
        }
    }
}

/** The Sunmi built-in printer service (package woyou.aidlservice.jiuiv5, interface IWoyouService). */
class SunmiServiceDriver(context: Context) : VendorServiceDriver(context) {
    override val name = "sunmi"
    override val servicePackage = "woyou.aidlservice.jiuiv5"
    override val serviceAction = "woyou.aidlservice.jiuiv5.IWoyouService"

    override suspend fun sendBytes(binder: IBinder, bytes: ByteArray): PrintResult {
        val service = IWoyouService.Stub.asInterface(binder)
        return runSunmiPrint(
            bytes,
            init = { service.printerInit(it) },
            send = { data, callback -> service.sendRAWData(data, callback) },
        )
    }
}

/**
 * A1 round 2, fix 1 (AT-11): printerInit gets its own callback that completes nothing, so only the answer to sendRAWData decides
 * Sent or Failed. [init] and [send] are the two service calls, passed in so a test can stand in for the service.
 */
internal suspend fun runSunmiPrint(
    bytes: ByteArray,
    init: (ICallback) -> Unit,
    send: (ByteArray, ICallback) -> Unit,
    timeoutMs: Long = PRINT_TIMEOUT_MS,
): PrintResult {
    val done = CompletableDeferred<PrintResult>()
    val initCallback = object : ICallback.Stub() {
        override fun onRunResult(isSuccess: Boolean) = Unit

        override fun onReturnString(result: String?) = Unit

        override fun onRaiseException(code: Int, msg: String?) {
            done.complete(PrintResult.Failed(msg ?: "Printer error $code"))
        }

        override fun onPrintResult(code: Int, msg: String?) = Unit
    }
    val printCallback = object : ICallback.Stub() {
        override fun onRunResult(isSuccess: Boolean) {
            done.complete(if (isSuccess) PrintResult.Sent else PrintResult.Failed("Printer refused the bill"))
        }

        override fun onReturnString(result: String?) = Unit

        override fun onRaiseException(code: Int, msg: String?) {
            done.complete(PrintResult.Failed(msg ?: "Printer error $code"))
        }

        override fun onPrintResult(code: Int, msg: String?) = Unit
    }
    init(initCallback)
    send(bytes, printCallback)
    return try {
        withTimeout(timeoutMs) { done.await() }
    } catch (e: TimeoutCancellationException) {
        PrintResult.Failed("Printer did not answer")
    }
}
