package app.verdant.search

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class WatchProgressTest {
    @Test fun unfinishedVideosResumeSlightlyBeforeTheSavedPosition() {
        assertEquals(598,resumePosition(WatchEntry(600,1800,1)))
        assertEquals(0,resumePosition(null))
        assertEquals(0,resumePosition(WatchEntry(3,1800,1)))
    }
    @Test fun finishedVideosStartAgainFromTheBeginning() {
        assertTrue(WatchEntry(1790,1800,1).finished)
        assertTrue(WatchEntry(960,1000,1).finished)
        assertFalse(WatchEntry(900,1000,1).finished)
        assertEquals(0,resumePosition(WatchEntry(1800,1800,1)))
        // Unknown duration never counts as finished.
        assertFalse(WatchEntry(5000,0,1).finished)
        assertEquals(4998,resumePosition(WatchEntry(5000,0,1)))
    }
    @Test fun anExplicitTimestampWinsOverTheSavedPosition() {
        assertEquals(75,resumePosition(WatchEntry(600,1800,1),75))
        assertEquals(0,resumePosition(WatchEntry(600,1800,1),0))
        assertEquals(598,resumePosition(WatchEntry(600,1800,1),-1))
    }
    @Test fun storedEntriesRoundTripAndRejectMalformedValues() {
        val entry=WatchEntry(125,3600,1_700_000_000_000)
        assertEquals(entry,parseWatchEntry(encodeWatchEntry(entry)))
        for(bad in listOf(null,"","1,2","a,b,c","-1,10,5","1,-10,5","1,2,3,4")) assertNull(bad,parseWatchEntry(bad))
        assertEquals(0.5f,WatchEntry(50,100,0).fraction,0.0001f)
        assertEquals(0f,WatchEntry(50,0,0).fraction,0.0001f)
    }
    @Test fun historyKeepsTheMostRecentlyWatchedVideos() {
        val entries=(1..10).associate {"video$it" to WatchEntry(it,100,it.toLong())}
        val kept=pruneWatchHistory(entries,3)
        assertEquals(setOf("video8","video9","video10"),kept.keys)
        assertSame(entries,pruneWatchHistory(entries,10))
    }
    @Test fun playerHtmlStartsAtTheResumePositionOnlyWhenNeeded() {
        val resumed=playerHtml("HlPe33BYvE4","app.verdant.search",1.0,598)
        assertTrue(resumed.contains("origin:'https://app.verdant.search',start:598}"))
        assertTrue(resumed.contains(PLAYER_PROGRESS_SCRIPT))
        assertTrue(resumed.contains("playerProgress.stateChanged(e.data)"))
        assertFalse(playerHtml("HlPe33BYvE4","app.verdant.search").contains("start:"))
        assertThrows(IllegalArgumentException::class.java){playerHtml("HlPe33BYvE4","app.verdant.search",1.0,-5)}
    }
    @Test fun cardsShowAShortPublicationAge() {
        val now=Instant.parse("2026-10-17T12:00:00Z")
        fun label(at: String)=relativeTimeLabel(at,now,ZoneOffset.UTC)
        assertEquals("Just now",label("2026-10-17T11:59:30Z"))
        assertEquals("Just now",label("2026-10-17T12:05:00Z"))
        assertEquals("12 min ago",label("2026-10-17T11:48:00Z"))
        assertEquals("3 h ago",label("2026-10-17T09:00:00Z"))
        assertEquals("Yesterday",label("2026-10-16T08:00:00Z"))
        assertEquals("4 d ago",label("2026-10-13T12:00:00Z"))
        assertEquals("2 Oct",label("2026-10-02T12:00:00Z"))
        assertEquals("2 Oct 2025",label("2025-10-02T12:00:00Z"))
        assertNull(label(""))
        assertNull(label("not a date"))
    }
    @Test fun continueWatchingListsRecentUnfinishedKnownVideosNewestFirst() {
        val day=86_400_000L;val now=100*day
        fun video(id:String,title:String="Title")=Video(id,title,"Channel","2026-10-17T10:00:00Z","",null,null,true,"UC1")
        val watch=mapOf(
            "recent00001" to WatchEntry(120,600,now-day),"newest00001" to WatchEntry(300,600,now-1000),"brief000001" to WatchEntry(60,600,now),
            "finished001" to WatchEntry(595,600,now),"barely00001" to WatchEntry(2,600,now),
            "old00000001" to WatchEntry(120,600,now-15*day),"unknown0001" to WatchEntry(120,600,now),
            "placeholder" to WatchEntry(120,600,now))
        val videos=listOf("recent00001","newest00001","brief000001","finished001","barely00001","old00000001").map {video(it)}+video("placeholder","Saved video")
        assertEquals(listOf("newest00001","recent00001"),continueWatching(watch,videos,now).map {it.first.id})
        val many=(1..30).associate {"video%06d".format(it) to WatchEntry(200,600,now-it)}
        assertEquals(CONTINUE_WATCHING_MAX,continueWatching(many,many.keys.map {video(it)},now).size)
    }
    @Test fun aVideoJoinsTheHistoryOnlyAfterTwoMinutesOfPlayback() {
        assertFalse(shouldTrackWatch(null,5,5,600))
        assertFalse(shouldTrackWatch(null,119,900,3600))
        assertTrue(shouldTrackWatch(null,120,130,600))
        // Seeking far ahead without watching does not count.
        assertFalse(shouldTrackWatch(null,3,1800,3600))
        // A short clip played to the end counts as watched.
        assertTrue(shouldTrackWatch(null,45,45,45))
        assertFalse(shouldTrackWatch(null,45,45,0))
        // Videos already in the history keep their position up to date.
        assertTrue(shouldTrackWatch(WatchEntry(400,600,1),2,402,600))
    }
}
