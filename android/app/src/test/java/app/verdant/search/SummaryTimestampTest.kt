package app.verdant.search

import org.junit.Assert.*
import org.junit.Test

class SummaryTimestampTest {
    private val id="aaaaaaaaaaa"
    @Test fun minuteAndHourTimestampsIncludeZeroAndLongVideos() {
        assertEquals(0,timestampSeconds("0:00"));assertEquals(90,timestampSeconds("01:30"))
        assertEquals(3723,timestampSeconds("1:02:03"));assertEquals(754L*60,timestampSeconds("754:00")?.toLong())
        for(invalid in listOf("12","1:2","1:60","1:60:00","-1:23","1000:00","1:02:03:04"))assertNull(invalid,timestampSeconds(invalid))
    }
    @Test fun cuesFindExactRangesWithoutPartialTimesOrUrlFragments() {
        val text="[0:05] Intro; (1:02:03–1:03:00) Demo. Wrong: 1:60:00, 1000:00, 12:34x. URL https://example.com/?t=12:34"
        val cues=summaryTimestamps(text)
        assertEquals(listOf(5,3723,3780),cues.map {it.seconds})
        assertEquals(listOf("0:05","1:02:03","1:03:00"),cues.map {text.substring(it.start,it.end)})
    }
    @Test fun timestampLinksRouteOnlyTheCurrentlyPlayingVideo() {
        assertEquals(90,summaryLinkTimestamp("https://www.youtube.com/watch?v=$id&t=90s",id))
        assertEquals(90,summaryLinkTimestamp("https://youtu.be/$id?t=1m30s",id))
        assertEquals(3723,summaryLinkTimestamp("https://m.youtube.com/watch?v=$id&t=1h2m3s",id))
        assertEquals(90,summaryLinkTimestamp("https://youtube.com/embed/$id?start=90",id))
        assertEquals(90,summaryLinkTimestamp("https://www.youtube.com/watch?v=$id&t=1%3A30",id))
        assertEquals(0,summaryLinkTimestamp("https://www.youtube.com/watch?v=$id&t=0",id))
        assertNull(summaryLinkTimestamp("https://www.youtube.com/watch?v=bbbbbbbbbbb&t=90",id))
        assertNull(summaryLinkTimestamp("https://www.youtube.com/watch?v=$id",id))
    }
    @Test fun hostileOrMalformedLinksCannotBecomeSeeks() {
        for(link in listOf("javascript:alert(1)","https://youtube.com.evil.test/watch?v=$id&t=90",
            "https://attacker@www.youtube.com/watch?v=$id&t=90","https://www.youtube.com:8443/watch?v=$id&t=90",
            "https://youtu.be/$id/extra?t=90","https://www.youtube.com/watch?v=$id&t=-1",
            "https://www.youtube.com/watch?v=$id&t=999999999999999999999999h",
            "https://www.youtube.com/watch?v=$id&t=2147483648","https://www.youtube.com/watch?v=$id&t=%GG"))
            assertNull(link,summaryLinkTimestamp(link,id))
        assertNull(summaryLinkTimestamp("https://www.youtube.com/watch?v=$id&t=90","';alert(1)"))
    }
    @Test fun duplicateVideoParameterCannotSelectAnotherVideoForSeek() {
        assertNull(summaryLinkTimestamp("https://www.youtube.com/watch?v=bbbbbbbbbbb&v=$id&t=30",id))
    }
    @Test fun timedLinksWithAWrongVideoIdStillSeekThisVideo() {
        assertEquals(0,summarySeekTarget("https://youtu.be/1mEeg-J6LgU?t=0s",id))
        assertEquals(95,summarySeekTarget("https://www.youtube.com/watch?v=bbbbbbbbbbb&t=1m35s",id))
        assertEquals(90,summarySeekTarget("https://youtu.be/$id?t=90",id))
        // Untimed links and other sites keep opening normally.
        assertNull(summarySeekTarget("https://youtu.be/bbbbbbbbbbb",id))
        assertNull(summarySeekTarget("https://example.com/watch?v=$id&t=90",id))
        assertNull(summarySeekTarget("http://youtu.be/bbbbbbbbbbb?t=90",id))
        assertNull(summarySeekTarget("javascript:alert(1)",id))
    }
    @Test fun seekLabelsRemainReadableForMinuteAndHourPositions() {
        assertEquals("0:00",timestampLabel(0));assertEquals("12:34",timestampLabel(754))
        assertEquals("1:02:03",timestampLabel(3723))
    }
}
