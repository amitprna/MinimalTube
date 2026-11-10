package app.verdant.search

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class RefreshCadenceTest {
    private val daily=RefreshCadence(daily=true,hour=18)
    private val hourly=RefreshCadence(daily=false)
    private fun at(time: String,day: Int=1)=ZonedDateTime.parse("2026-10-%02dT%s+05:30[Asia/Kolkata]".format(day,time))
    private fun stamp(time: String,day: Int=1)=at(time,day).toInstant().toEpochMilli()

    @Test fun dailyDoesNotCatchUpYesterdaysMissedRunBeforeTodaysTime() {
        assertFalse(daily.isDue(at("17:00:00"),0,null))
        assertFalse(daily.isDue(at("17:59:59",2),stamp("18:00:00"),"2026-10-01"))
    }
    @Test fun dailyIsDueAtScheduledTimeOrWhenOpenedLater() {
        assertTrue(daily.isDue(at("18:00:00"),0,null))
        assertTrue(daily.isDue(at("18:30:00"),stamp("17:00:00"),null))
    }
    @Test fun dailyDoesNotRefreshAgainAfterReopeningEvenHoursLater() {
        assertFalse(daily.isDue(at("18:30:00"),stamp("18:00:00"),"2026-10-01"))
        assertFalse(daily.isDue(at("23:59:59"),stamp("18:00:00"),"2026-10-01"))
    }
    @Test fun followingDayWaitsForItsOwnTime() {
        assertFalse(daily.isDue(at("00:01:00",2),stamp("18:30:00"),"2026-10-01"))
        assertTrue(daily.isDue(at("18:00:00",2),stamp("18:30:00"),"2026-10-01"))
    }
    @Test fun changingTimeOrTogglingModeDoesNotForgetTodaysDailyRun() {
        assertFalse(RefreshCadence(true,20).isDue(at("20:30:00"),stamp("18:00:00"),"2026-10-01"))
        assertFalse(daily.isDue(at("19:00:00"),stamp("18:00:00"),"2026-10-01"))
    }
    @Test fun hourlyUsesOneHourThresholdRatherThanAppOpenOrChannelAge() {
        assertFalse(hourly.isDue(at("18:30:00"),stamp("18:00:00"),null))
        assertFalse(hourly.isDue(at("18:59:59"),stamp("18:00:00"),null))
        assertTrue(hourly.isDue(at("19:00:00"),stamp("18:00:00"),null))
        assertTrue(hourly.isDue(at("23:00:00"),stamp("18:00:00"),null))
    }
    @Test fun hourlyFirstRunIsDueAndDailyMarkerDoesNotBlockHourlyMode() {
        assertTrue(hourly.isDue(at("17:00:00"),0,null))
        assertTrue(hourly.isDue(at("19:00:00"),stamp("18:00:00"),"2026-10-01"))
    }
    @Test fun disablingDailyModeStillWaitsAnHourAfterRecentRun() {
        assertFalse(hourly.isDue(at("18:30:00"),stamp("18:00:00"),"2026-10-01"))
        assertTrue(hourly.isDue(at("19:00:00"),stamp("18:00:00"),"2026-10-01"))
    }
    @Test fun manualCheckBeforeDailyTimeDoesNotReplaceScheduleButCheckAfterDoes() {
        assertNull(daily.dayFulfilledBy(at("17:00:00")))
        assertTrue(daily.isDue(at("18:00:00"),stamp("17:00:00"),null))
        assertEquals("2026-10-01",daily.dayFulfilledBy(at("18:00:00")))
        assertFalse(daily.isDue(at("18:30:00"),stamp("18:10:00"),null))
    }
    @Test fun queuedHourlyWorkCannotStartAfterSwitchingToDailyBeforeTime() {
        val last=stamp("15:00:00")
        assertTrue(hourly.isDue(at("17:00:00"),last,null))
        assertFalse(daily.isDue(at("17:05:00"),last,null))
    }
    @Test fun midnightIsAValidDailyTime() {
        val midnight=RefreshCadence(true,0)
        assertTrue(midnight.isDue(at("00:00:00",2),stamp("00:00:00"),"2026-10-01"))
        assertFalse(midnight.isDue(at("00:01:00",2),stamp("00:00:00",2),"2026-10-02"))
    }
    @Test fun dstMissingAndRepeatedTimesStillRunOncePerLocalDay() {
        val schedule=RefreshCadence(true,2,30)
        val before=ZonedDateTime.parse("2026-03-08T03:15:00-04:00[America/New_York]")
        assertFalse(schedule.isDue(before,0,null))
        assertTrue(schedule.isDue(before.plusMinutes(15),0,null))
        val first=ZonedDateTime.parse("2026-11-01T01:30:00-04:00[America/New_York]")
        val second=ZonedDateTime.parse("2026-11-01T01:30:00-05:00[America/New_York]")
        val repeated=RefreshCadence(true,1,30)
        assertTrue(repeated.isDue(first,0,null))
        assertFalse(repeated.isDue(second,first.toInstant().toEpochMilli(),repeated.dayFulfilledBy(first)))
    }
}
