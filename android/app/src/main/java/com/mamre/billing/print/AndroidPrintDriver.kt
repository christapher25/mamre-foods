package com.mamre.billing.print

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream

/** A1 item 2c: the system print dialog. Last in the chain because it needs a tap. The 32 lines are drawn in monospace. */
class AndroidPrintDriver(private val context: Context) : PrinterDriver {
    override val name = "android"
    override val rememberable = false

    override fun isAvailable(): Boolean = context.getSystemService(Context.PRINT_SERVICE) is PrintManager

    override suspend fun print(doc: PrintDocument): PrintResult {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return PrintResult.Failed("No print service")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize("mamre58", "58 mm roll", PrintPage.widthMils(), PrintPage.heightMils(doc.lines.size)))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        manager.print("Mamre bill", ReceiptAdapter(context, doc.lines), attributes)
        return PrintResult.Sent
    }
}

private class ReceiptAdapter(private val context: Context, private val lines: List<String>) : PrintDocumentAdapter() {
    private var pdf: PrintedPdfDocument? = null

    override fun onLayout(
        old: PrintAttributes?,
        new: PrintAttributes,
        cancel: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        pdf = PrintedPdfDocument(context, new)
        val info = PrintDocumentInfo.Builder("bill.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancel: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val document = pdf ?: return callback.onWriteFailed("not laid out")
        val page = document.startPage(0)
        val paint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = PrintPage.FONT_PT
            isAntiAlias = true
        }
        val left = ((PrintPage.PAGE_MM - PrintPage.PRINTABLE_MM) / 2 / PrintPage.MM_PER_PT).toFloat()
        var y = PrintPage.lineHeightPt()
        for (line in lines) {
            page.canvas.drawText(line, left, y, paint)
            y += PrintPage.lineHeightPt()
        }
        document.finishPage(page)
        try {
            document.writeTo(FileOutputStream(destination.fileDescriptor))
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: java.io.IOException) {
            callback.onWriteFailed(e.message)
        } finally {
            document.close()
            pdf = null
        }
    }
}
