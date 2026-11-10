package app.verdant.search

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryActionsTest {
    @Test fun newlyFetchedChannelVideoDoesNotInheritOldLibraryDateButMovingOldSavedVideoDoes() {
        val now=40L*86_400_000
        val old=video("old00000001");val fresh=video("fresh000001")
        val data=LibraryData(syncedAt=1,feed=listOf(old))
        val saved=moveVideo(data,fresh,1,now).first
        assertEquals(now,saved.videoSyncedAt[fresh.id])
        assertEquals(fresh,pruneLibrary(saved,now).later.single().video)
        val moved=moveVideo(data,old,1,now).first
        assertEquals(1L,moved.videoSyncedAt[old.id])
        assertEquals("Saved video",pruneLibrary(moved,now).later.single().video.title)
    }
    private fun video(id:String="aaaaaaaaaaa",channel:String="UC1")=Video(id,id,channel,"2026-09-22T00:00:00Z","","PT10M",null,true,channel)
    @Test fun undoBinRestoresHomeAndPreservesAnUnrelatedSave() {
        val a=video();val b=video("bbbbbbbbbbb")
        val initial=LibraryData(channels=listOf(Channel("UC1","Channel")),feed=listOf(a,b))
        val (binned,undo)=moveVideo(initial,a,2,100)
        assertFalse(visibleHome(binned).contains(a))
        val anotherSave=moveVideo(binned,b,1,101).first
        val restored=undoVideoMove(anotherSave,undo)
        assertEquals(listOf(a),visibleHome(restored));assertTrue(restored.bin.isEmpty())
        assertEquals(b,restored.later.single().video)
    }
    @Test fun undoFromWatchLaterToBinRestoresOriginalTimestampAndSurvivesStorage() {
        val a=video();val initial=LibraryData(later=listOf(SavedVideo(a,50)))
        val (moved,undo)=moveVideo(initial,a,2,100)
        val restored=LibraryStore.decode(LibraryStore.encode(undoVideoMove(moved,undo)))
        assertEquals(50L,restored.later.single().savedAt);assertTrue(restored.bin.isEmpty())
    }
    @Test fun googleImportRemovesUnsubscribedChannelsButKeepsOrganisationAndSavedVideos() {
        val old=LibraryData(channels=listOf(Channel("UC1","Old"),Channel("UC2","Keep")),feed=listOf(video(),video("bbbbbbbbbbb","UC2")),groups=setOf("Tech"),groupIds=mapOf("Tech" to "TECH"),rules=mapOf("UC1" to ChannelRule(groups=setOf("Tech"))),later=listOf(SavedVideo(video(),50)))
        val updated=reconcileSubscriptions(old,listOf(Channel("UC2","New title")))
        assertEquals(listOf("UC2"),updated.channels.map {it.id});assertEquals("New title",updated.channels.single().title)
        assertEquals(listOf("UC2"),updated.feed.map {it.channelId});assertEquals(old.groups,updated.groups);assertEquals(old.rules,updated.rules);assertEquals(old.later,updated.later)
        val empty=reconcileSubscriptions(updated,emptyList())
        assertTrue(empty.channels.isEmpty());assertTrue(empty.feed.isEmpty());assertEquals(old.groups,empty.groups)
    }
    @Test fun organisationRecoveryKeepsBothLibrariesAndCanBeRepeatedSafely() {
        val current=LibraryData(groups=setOf("Health"),groupIds=mapOf("Health" to "HLTH"),rules=mapOf("UC1" to ChannelRule(minimumSeconds=600,groups=setOf("Health"))))
        val previous=LibraryData(groups=setOf("Tech"),groupIds=mapOf("Tech" to "TECH"),rules=mapOf("UC1" to ChannelRule(groups=setOf("Tech"))))
        val recovered=recoverOrganisation(current,previous)
        assertEquals(setOf("Health","Tech"),recovered.groups);assertEquals(recovered.groups,recovered.rules.getValue("UC1").groups)
        assertEquals(600L,recovered.rules.getValue("UC1").minimumSeconds)
        assertEquals(recovered,recoverOrganisation(recovered,previous))
        assertEquals(recovered,LibraryStore.decode(LibraryStore.encode(recovered)))
    }
    @Test fun groupEditKeepsAConcurrentBackgroundFeedRefresh() {
        val before=LibraryData(feed=listOf(video()))
        val latest=before.copy(feed=listOf(video("bbbbbbbbbbb")),channelSyncedAt=mapOf("UC1" to 100L))
        val result=mergeLibraryEdit(latest,before,before.copy(groups=setOf("Tech")))
        assertEquals(latest.feed,result.feed);assertEquals(latest.channelSyncedAt,result.channelSyncedAt);assertEquals(setOf("Tech"),result.groups)
    }
    @Test fun refreshConcurrencyIsBoundedAndFailuresDoNotApplyEmptyPages() = runTest {
        var active=0;var peak=0;val applied=mutableListOf<String>()
        val channels=List(228){Channel("UC$it","$it")}
        val failures=refreshChannelBatch(channels,{c->
            active++;peak=maxOf(peak,active);delay(50);active--
            if(c.id=="UC2")throw IllegalStateException("offline")
            UploadPage(emptyList(),null)
        },{c,_->applied+=c.id})
        assertEquals(3,peak);assertEquals(3800L,testScheduler.currentTime)
        assertEquals(1,failures);assertFalse("UC2" in applied);assertEquals(227,applied.size)
    }
    @Test fun partialRefreshDoesNotRenewOldChannelMetadata() {
        val now=40L*24*3600*1000
        val data=LibraryData(feed=listOf(video(),video("bbbbbbbbbbb","UC2")),syncedAt=1,channelSyncedAt=mapOf("UC1" to now))
        val restored=LibraryStore.decode(LibraryStore.encode(data))
        val pruned=pruneLibrary(restored,now)
        assertEquals(listOf("UC1","UC2"),pruned.feed.map {it.channelId})
        assertEquals("Saved video",pruned.feed.last().title)
        assertEquals("aaaaaaaaaaa",pruned.feed.first().title)
    }
    @Test fun dailyRefreshPreservesBacklogAndDoesNotRenewUnfetchedMetadata() {
        val channel=Channel("UC1","Channel");val old=video()
        val before=LibraryData(channels=listOf(channel),feed=listOf(old),syncedAt=100)
        val recent=video("bbbbbbbbbbb").copy(publishedAt="2026-09-23T00:00:00Z")
        val after=mergeRecentUploads(before,channel,listOf(recent),200)
        assertEquals(listOf(recent,old),after.feed);assertEquals(100L,videoMetadataTime(after,old));assertEquals(200L,videoMetadataTime(after,recent))
        assertEquals(after.feed,mergeRecentUploads(after,channel,emptyList(),300).feed)
        assertEquals(after,LibraryStore.decode(LibraryStore.encode(after)).copy(feed=after.feed))
    }
}
