package app.verdant.search

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LibraryTest {
    @Test fun savedMetadataExpiresPerVideoAndOrphanTimestampsArePruned() {
        val now=40L*86_400_000
        val fresh=video("fresh000001");val old=video("old00000001")
        val data=LibraryData(syncedAt=1,later=listOf(SavedVideo(fresh,now),SavedVideo(old,now)),videoSyncedAt=mapOf(fresh.id to now-60_000,old.id to 1,"orphan" to 100))
        val pruned=pruneLibrary(data,now)
        assertEquals(fresh,pruned.later.first().video);assertEquals("Saved video",pruned.later.last().video.title)
        assertFalse("orphan" in pruned.videoSyncedAt)
    }
    @Test fun availabilitySurvivesStorageAndLegacyUnknownStaysUnknown() {
        for(available in listOf(true,false,null)) {
            val original=video().copy(available=available)
            assertEquals(original,LibraryStore.decode(LibraryStore.encode(LibraryData(feed=listOf(original)))).feed.single())
        }
    }
    private fun video(id: String="aaaaaaaaaaa", channel: String="UC1",duration: String="PT10M",date: String="2026-09-17T10:00:00Z") = Video(id,id,channel,date,"",duration,null,true,channel)
    @Test fun homeContainsOnlySubscriptionsAndExcludesSavedAndBinnedVideos() {
        val a=video(); val b=video("bbbbbbbbbbb");val outside=video("ccccccccccc","UC2")
        val data=LibraryData(channels=listOf(Channel("UC1","One")),feed=listOf(a,b,outside),later=listOf(SavedVideo(a,1)))
        assertEquals(listOf(b),visibleHome(data))
        assertTrue(visibleHome(data.copy(bin=listOf(SavedVideo(b,1)))).isEmpty())
    }
    @Test fun filtersAndGroupsUseStrictMinimumAndNewestFirst() {
        val short=video(duration="PT3M");val older=video("bbbbbbbbbbb",date="2026-09-16T10:00:00Z");val newer=video("ccccccccccc")
        val data=LibraryData(channels=listOf(Channel("UC1","One")),feed=listOf(short,older,newer),rules=mapOf("UC1" to ChannelRule(true,180,setOf("Health"))),groups=setOf("Health","Other"))
        assertEquals(listOf(newer,older),visibleHome(data,"Health"))
        assertTrue(visibleHome(data,"Other").isEmpty())
        assertEquals(listOf(older),visibleHome(data.copy(videoGroups=mapOf(older.id to setOf("Other"))),"Other"))
    }
    @Test fun binnedVideosLeaveTheBinAfterExactly24Hours() {
        val start=Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
        val expiry=Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
        assertEquals(expiry,binExpiry(start))
        val data=LibraryData(bin=listOf(SavedVideo(video(),start)),feed=listOf(video()))
        assertEquals(1,pruneLibrary(data,expiry-1).bin.size)
        assertTrue(pruneLibrary(data,expiry).bin.isEmpty())
        assertTrue(pruneLibrary(data,expiry).feed.isEmpty())
    }
    @Test fun tagsForChannelPageVideosSurviveBeforeSavingToLibrary() {
        val data=LibraryData(videoGroups=mapOf("aaaaaaaaaaa" to setOf("Health")))
        assertEquals(data.videoGroups,pruneLibrary(data,123).videoGroups)
    }
    @Test fun libraryRoundTripPreservesLocalActionsAndGroups() {
        val data=LibraryData(channels=listOf(Channel("UC1","One")),feed=listOf(video()),later=listOf(SavedVideo(video(),123)),rules=mapOf("UC1" to ChannelRule(true,300,setOf("Health"))),groups=setOf("Health"))
        val decoded=LibraryStore.decode(LibraryStore.encode(data))
        assertEquals(data.rules,decoded.rules);assertEquals(data.later.first().savedAt,decoded.later.first().savedAt)
        assertEquals(data.feed.first().channelId,decoded.feed.first().channelId)
    }

    @Test fun ungroupedUsesBothChannelAndVideoTags() {
        val a=video();val b=video("bbbbbbbbbbb","UC2")
        val data=LibraryData(channels=listOf(Channel("UC1","One"),Channel("UC2","Two")),feed=listOf(a,b),groups=setOf("Tech"),rules=mapOf("UC1" to ChannelRule(groups=setOf("Tech"))))
        assertEquals(listOf(b),visibleHome(data,UNGROUPED))
        assertEquals(listOf(a),visibleHome(data,"Tech"))
        assertTrue(visibleHome(data.copy(videoGroups=mapOf(b.id to setOf("Tech"))),UNGROUPED).isEmpty())
        assertEquals(2,visibleHome(data).size)
    }
    @Test fun groupIdsMigrateWithoutCollisionsAndRoundTrip() {
        val groups=setOf("Technology","Technical","All")
        val codes=groupCodes(groups,emptyMap())
        assertEquals(3,codes.values.toSet().size)
        assertTrue(codes.values.all {it.length<=5 && it!="ALL"})
        val data=LibraryData(groups=groups,groupIds=codes)
        assertEquals(codes,LibraryStore.decode(LibraryStore.encode(data)).groupIds)
    }
    @Test fun legacyShortsPresetShowsThreeMinutesAndPreservesHigherCustomMinimum() {
        assertEquals(180L,durationMinimum(ChannelRule(hideShorts=true)))
        assertEquals(600L,durationMinimum(ChannelRule(hideShorts=true,minimumSeconds=600)))
        assertEquals(0L,durationMinimum(ChannelRule()))
    }
    @Test fun staleMetadataDoesNotDeleteUnreadFeedOrGroupAssignment() {
        val data=LibraryData(channels=listOf(Channel("UC1","One")),feed=listOf(video()),syncedAt=1,groups=setOf("Tech"),rules=mapOf("UC1" to ChannelRule(true,180,setOf("Tech"))))
        val old=pruneLibrary(data,40L*86400*1000)
        assertEquals(listOf("aaaaaaaaaaa"),visibleHome(old,"Tech").map {it.id})
        assertEquals("Saved video",old.feed.single().title)
        assertNull(old.feed.single().duration)
    }
    @Test fun binRemovalTimeReadsAsTodayOrTomorrow() {
        val zone=java.time.ZoneOffset.UTC
        val now=Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()
        assertEquals("Removed today at 21:30",binRemovalLabel(Instant.parse("2026-10-28T21:30:00Z").toEpochMilli(),now,zone))
        assertEquals("Removed tomorrow at 08:05",binRemovalLabel(Instant.parse("2026-10-29T08:05:00Z").toEpochMilli(),now,zone))
        assertEquals("Removed 2026-10-31 at 10:00",binRemovalLabel(Instant.parse("2026-10-31T10:00:00Z").toEpochMilli(),now,zone))
    }
    @Test fun hiddenVideosLeaveTheStoredFeedButStayHiddenAndRestorable() {
        val shown=video("shown000001");val saved=video("saved000001");val binned=video("binned00001");val dismissed=video("dismissed01")
        val data=LibraryData(channels=listOf(Channel("UC1","One")),feed=listOf(shown,saved,binned,dismissed),syncedAt=1,
            later=listOf(SavedVideo(saved,5)),bin=listOf(SavedVideo(binned,5)),dismissed=setOf(dismissed.id))
        val pruned=pruneLibrary(data,10)
        assertEquals(listOf(shown),pruned.feed)
        assertEquals(setOf(dismissed.id,binned.id,saved.id),pruned.dismissed)
        assertEquals(listOf(shown),visibleHome(pruned))
        // Returning a saved video to Home puts it back in the feed.
        val returned=pruneLibrary(moveVideo(pruned,saved,0,20).first,20)
        assertEquals(setOf(shown.id,saved.id),visibleHome(returned).map {it.id}.toSet())
        assertTrue(returned.later.isEmpty())
    }
    @Test fun onlyTheNewestDismissedIdsAreKept() {
        val ids=(1..7000).map {"id%07d".format(it)}.toCollection(LinkedHashSet())
        val kept=limitDismissed(ids)
        assertEquals(MAX_DISMISSED_IDS,kept.size);assertTrue("id0007000" in kept);assertFalse("id0000001" in kept)
        val few=setOf("a","b");assertSame(few,limitDismissed(few))
    }
    @Test fun savingAVideoRemovesItFromContinueWatching() {
        val now=100L*86_400_000
        val started=video("started0001");val other=video("other000001")
        val data=LibraryData(channels=listOf(Channel("UC1","One")),feed=listOf(started,other),syncedAt=now)
        val watch=mapOf(started.id to WatchEntry(300,600,now),other.id to WatchEntry(200,600,now-1))
        assertEquals(listOf(started.id,other.id),continueWatching(watch,visibleHome(data),now).map {it.first.id})
        val saved=pruneLibrary(moveVideo(data,started,1,now).first,now)
        assertEquals(listOf(other.id),continueWatching(watch,visibleHome(saved),now).map {it.first.id})
        val binned=pruneLibrary(moveVideo(saved,other,2,now).first,now)
        assertTrue(continueWatching(watch,visibleHome(binned),now).isEmpty())
        // Returning it to Home brings it back.
        val returned=pruneLibrary(moveVideo(binned,started,0,now).first,now)
        assertEquals(listOf(started.id),continueWatching(watch,visibleHome(returned),now).map {it.first.id})
    }
}
