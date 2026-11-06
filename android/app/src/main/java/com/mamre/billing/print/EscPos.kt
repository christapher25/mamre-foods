package com.mamre.billing.print

import java.io.ByteArrayOutputStream

/**
 * ESC/POS bytes for a 32-column receipt (A1 item 2b). The text comes only from the receipt builders. Header lines (everything
 * before the first rule line) are centred and bold; the body is left aligned. ASCII only: any other character becomes '?'.
 */
object EscPos {
    private val INIT = byteArrayOf(0x1B, 0x40)
    private val CENTRE_BOLD = byteArrayOf(0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01)
    private val LEFT_NORMAL = byteArrayOf(0x1B, 0x45, 0x00, 0x1B, 0x61, 0x00)
    private const val LF: Byte = 0x0A
    private const val END_FEEDS = 3

    fun encode(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)
        val headerCount = lines.indexOfFirst(::isRule).let { if (it < 0) 0 else it }
        if (headerCount > 0) out.write(CENTRE_BOLD)
        lines.forEachIndexed { i, line ->
            if (i == headerCount && headerCount > 0) out.write(LEFT_NORMAL)
            val text = if (i < headerCount) line.trim() else line
            out.write(ascii(text))
            out.write(LF.toInt())
        }
        repeat(END_FEEDS) { out.write(LF.toInt()) }
        return out.toByteArray()
    }

    private fun isRule(line: String) = line.isNotEmpty() && line.all { it == '-' }

    private fun ascii(text: String): ByteArray =
        ByteArray(text.length) { i -> text[i].let { if (it.code in 0x20..0x7E) it.code.toByte() else '?'.code.toByte() } }
}
