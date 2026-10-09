package com.localtv.viewer.data

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Normalize protocol timestamps once, before catalog indexing or UI composition. */
object MediaTime {
    fun parse(value: String): Long {
        if (value.isBlank()) return 0
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)).toInstant().toEpochMilli() }.getOrDefault(0)
        }.coerceAtLeast(0)
    }
}
