package app.verdant.search

import java.time.ZonedDateTime

/** The same admission rule is used by foreground and background refreshes. */
data class RefreshCadence(val daily: Boolean, val hour: Int = 8, val minute: Int = 0) {
    private fun scheduledToday(now: ZonedDateTime) = now.toLocalDate()
        .atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(now.zone)

    fun isDue(now: ZonedDateTime, lastStartedAt: Long, dailyRunDay: String?): Boolean {
        if (!daily) return lastStartedAt == 0L || now.toInstant().toEpochMilli() - lastStartedAt >= HOUR_MS
        val scheduled = scheduledToday(now)
        return !now.isBefore(scheduled) && dailyRunDay != now.toLocalDate().toString() &&
            lastStartedAt < scheduled.toInstant().toEpochMilli()
    }

    // A manual check after today's scheduled time also fulfils today's refresh.
    // Before that time, it leaves the scheduled refresh available.
    fun dayFulfilledBy(now: ZonedDateTime): String? =
        now.toLocalDate().toString().takeIf { daily && !now.isBefore(scheduledToday(now)) }

    companion object { const val HOUR_MS = 60 * 60_000L }
}
