package app.verdant.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FeedSyncTest {
    private val target=Instant.parse("2026-09-25T13:00:00Z").toEpochMilli()
    private fun channels(n:Int)=List(n){Channel("UC$it","Channel $it",uploads="UU$it",metadataCheckedAt=target-60_000)}
    private fun video(id:String)=Video(id,id,"Channel",Instant.ofEpochMilli(target-60_000).toString(),"","PT10M",null,true,id.removePrefix("v-"))
    private inner class Source:FeedSource {
        val reads=mutableListOf<String>();val detailBatches=mutableListOf<List<String>>()
        var failing=setOf<String>();var missing=false
        override suspend fun channels(ids:List<String>)=error("Cached channel details should be reused")
        override suspend fun recentUploadIds(channel:Channel,since:Instant,until:Instant):List<String> {
            reads+=channel.id
            if(channel.id in failing)throw SearchFailure("channel","Unavailable")
            return listOf("v-"+channel.id)
        }
        override suspend fun videos(ids:List<String>):List<Video> {detailBatches+=ids;return if(missing)emptyList() else ids.map(::video)}
    }
    @Test fun retriesOnlyFourFailuresOutOf228AndBatchesDetails()=runTest {
        var data=LibraryData(channels=channels(228));val remote=Source();remote.failing=setOf("UC1","UC12","UC54","UC220")
        val errors=mutableMapOf<String,String>()
        FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target)},{c,e->errors[c.id]=e.message.orEmpty()})
        assertEquals(224,data.channelSyncedAt.size);assertEquals(4,errors.size)
        assertTrue(remote.detailBatches.size<=29)
        remote.reads.clear();remote.detailBatches.clear();remote.failing=emptySet()
        // Reconstruct storage as a process restart would, retaining success checkpoints.
        data=LibraryStore.decode(LibraryStore.encode(data))
        FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target)},{_,e->throw e})
        assertEquals(errors.keys,remote.reads.toSet());assertEquals(228,data.channelSyncedAt.size)
        assertEquals(228,data.feed.size)
    }
    @Test fun delayedRunCoversGapSinceLastSuccessfulCheck() {
        assertEquals(target-86_400_000,syncSince(0,target).toEpochMilli())
        val threeDaysAgo=target-3*86_400_000L
        assertEquals(threeDaysAgo-7_200_000,syncSince(threeDaysAgo,target).toEpochMilli())
        assertEquals(target-150*60_000,syncSince(target-30*60_000,target).toEpochMilli())
    }
    @Test fun knownFreshVideosDoNotRequestDetailsAgain()=runTest {
        val c=channels(1);val v=video("v-UC0")
        var data=LibraryData(channels=c,feed=listOf(v),channelSyncedAt=mapOf("UC0" to target-3_600_000),videoSyncedAt=mapOf(v.id to target-3_600_000))
        val remote=Source()
        FeedSync(remote).run(target,{data},{channel,videos->data=mergeRecentUploads(data,channel,videos,target)},{_,e->throw e})
        assertTrue(remote.detailBatches.isEmpty());assertEquals(listOf(v),data.feed)
        assertEquals(target,data.channelSyncedAt["UC0"])
    }
    @Test fun privateOrRemovedUploadDoesNotBlockAnOtherwiseSuccessfulScan()=runTest {
        var data=LibraryData(channels=channels(1));val remote=Source();remote.missing=true
        var problem=""
        FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target)},{_,e->problem=(e as SearchFailure).code})
        assertEquals("",problem);assertEquals(target,data.channelSyncedAt["UC0"]);assertTrue(data.feed.isEmpty())
    }
    @Test fun coldLibraryBatches228ChannelLookupsIntoFiveRequests()=runTest {
        var data=LibraryData(channels=channels(228).map {it.copy(uploads="",metadataCheckedAt=0)})
        val batches=mutableListOf<List<String>>()
        val remote=object:FeedSource {
            override suspend fun channels(ids:List<String>):List<Channel>{batches+=ids;return ids.map {Channel(it,it,uploads=it.replaceFirst("UC","UU"),metadataCheckedAt=target)}}
            override suspend fun recentUploadIds(channel:Channel,since:Instant,until:Instant)=emptyList<String>()
            override suspend fun videos(ids:List<String>)=error("No new videos")
        }
        FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target)},{_,e->throw e})
        assertEquals(listOf(50,50,50,50,28),batches.map {it.size});assertEquals(228,data.channelSyncedAt.size)
    }
    @Test fun missingPrivateItemKeepsPublicUploadsButNetworkFailureKeepsCheckpointPending()=runTest {
        var data=LibraryData(channels=channels(1));var broken=false;var errors=0
        val remote=object:FeedSource {
            override suspend fun channels(ids:List<String>)=error("Cached")
            override suspend fun recentUploadIds(channel:Channel,since:Instant,until:Instant)=listOf("private","v-UC0")
            override suspend fun videos(ids:List<String>):List<Video>{if(broken)throw java.io.IOException("offline");return listOf(video("v-UC0"))}
        }
        FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target)},{_,_->errors++})
        assertEquals(listOf("v-UC0"),data.feed.map {it.id});assertEquals(0,errors)
        broken=true
        FeedSync(remote).run(target+60_000,{data},{c,v->data=mergeRecentUploads(data,c,v,target+60_000)},{_,_->errors++})
        assertEquals(1,errors);assertEquals(target,data.channelSyncedAt["UC0"])
    }
    @Test fun sharedQuotaFailureStopsFurtherReadsInsteadOfHammeringEveryChannel()=runTest {
        val data=LibraryData(channels=channels(228));var calls=0;var errors=0
        val remote=object:FeedSource {
            override suspend fun channels(ids:List<String>)=error("Cached")
            override suspend fun recentUploadIds(channel:Channel,since:Instant,until:Instant):List<String>{calls++;throw SearchFailure("quota_exhausted","Quota")}
            override suspend fun videos(ids:List<String>)=error("Quota already exhausted")
        }
        FeedSync(remote).run(target,{data},{_,_->fail("Must not checkpoint")},{_,_->errors++})
        assertTrue(calls<=3);assertEquals(228,errors)
    }
    @Test fun interruptionKeepsCompletedChannelsAndResumesOthers()=runTest {
        var data=LibraryData(channels=channels(8));val remote=Source()
        try {FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target);throw CancellationException()},{_,e->throw e})}
        catch(_:CancellationException){}
        assertEquals(1,data.channelSyncedAt.size)
        val done=data.channelSyncedAt.keys.single();remote.reads.clear()
        FeedSync(remote).run(target,{data},{c,v->data=mergeRecentUploads(data,c,v,target)},{_,e->throw e})
        assertFalse(done in remote.reads);assertEquals(8,data.channelSyncedAt.size)
    }
    @Test fun transientReadsRetryButDailyQuotaDoesNotSpin()=runTest {
        var calls=0
        assertEquals("ok",retrySyncRead {calls++;if(calls<2)throw java.io.IOException("offline");"ok"})
        assertEquals(2,calls)
        calls=0
        try {retrySyncRead {calls++;throw SearchFailure("quota_exhausted","Quota")};fail("Expected quota error")}
        catch(_:SearchFailure){}
        assertEquals(1,calls)
    }

}
