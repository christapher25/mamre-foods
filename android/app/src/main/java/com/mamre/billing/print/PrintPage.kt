package com.mamre.billing.print

/** A1 item 2c: the page for the Android print dialog: 58 mm paper, 48 mm printable, 32 monospace columns. */
object PrintPage {
    const val PAGE_MM = 58.0
    const val PRINTABLE_MM = 48.0
    const val MM_PER_PT = 25.4 / 72.0

    /** Largest half-point size at which 32 columns still fit 48 mm. */
    const val FONT_PT = 7f
    private const val LINE_HEIGHT_FACTOR = 1.2f
    private const val MARGIN_LINES = 3

    /** A monospace glyph is about 0.6 em wide. */
    fun glyphWidthPt(fontPt: Float): Double = fontPt * 0.6

    fun lineHeightPt(): Float = FONT_PT * LINE_HEIGHT_FACTOR

    /** Page height in thousandths of an inch (the unit PrintAttributes.MediaSize uses). */
    fun heightMils(lineCount: Int): Int = (((lineCount + MARGIN_LINES) * lineHeightPt()) / 72f * 1000f).toInt()

    fun widthMils(): Int = (PAGE_MM / 25.4 * 1000).toInt()
}
