package com.mamre.billing.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

/** LocalDate is stored as an ISO-8601 string such as 2026-10-10. */
class Converters {
    @TypeConverter fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
