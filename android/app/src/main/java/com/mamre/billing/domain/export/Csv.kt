package com.mamre.billing.domain.export

// RFC 4180 (Doc 2 s5.3): fields are separated by commas and records end in CRLF, the last one too. A field with a comma, a double
// quote or a line break is wrapped in double quotes and its quotes are doubled. Nothing else is changed, so a value reads back as
// it was stored. The text is encoded as UTF-8 when it is written to a file, with no byte order mark.

private const val CRLF = "\r\n"

private fun field(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

/** One record: the fields joined by commas, then CRLF. */
fun csvRecord(fields: List<String>): String = fields.joinToString(",") { field(it) } + CRLF

/** A whole file: the header row, then one record per row. With no rows it is the header alone. */
fun csvDocument(header: List<String>, rows: List<List<String>>): String =
    buildString {
        append(csvRecord(header))
        rows.forEach { append(csvRecord(it)) }
    }
