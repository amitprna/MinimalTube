package app.verdant.search

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MinimalTubeTest {
    private val now=Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
    private val channel=Channel("UC1","Channel",uploads="UU1",metadataCheckedAt=now)
    private val video=Video("aaaaaaaaaaa","Title","Channel","2026-09-29T11:00:00Z","","PT10M",null,true,"UC1")
    @Test fun expiredBinIdStaysHiddenAfterStorageAndRediscovery() {
        val original=LibraryData(channels=listOf(channel),feed=listOf(video),videoSyncedAt=mapOf(video.id to now))
        val moved=moveVideo(original,video,2,now).first
        val expired=pruneLibrary(moved,binExpiry(now))
        assertTrue(expired.bin.isEmpty());assertTrue(expired.feed.isEmpty())
        val restored=LibraryStore.decode(LibraryStore.encode(expired))
        val refreshed=mergeRecentUploads(restored,channel,listOf(video),now)
        assertTrue(visibleHome(refreshed).isEmpty());assertTrue(video.id in refreshed.dismissed)
    }
    @Test fun savedAndSuppressedVideosAreNotReEnrichedOnRefresh()=runTest {
        for(destination in 1..2) {
            var data=moveVideo(LibraryData(channels=listOf(channel),feed=listOf(video)),video,destination,now).first
            val source=object:FeedSource {
                override suspend fun channels(ids:List<String>)=error("Already cached")
                override suspend fun recentUploadIds(channel:Channel,since:Instant,until:Instant)=listOf(video.id)
                override suspend fun videos(ids:List<String>)=error("Do not fetch dismissed videos again")
            }
            FeedSync(source).run(now,{data},{c,v->data=mergeRecentUploads(data,c,v,now)},{_,e->throw e})
            assertTrue(visibleHome(data).isEmpty());assertEquals(now,data.channelSyncedAt[channel.id])
        }
    }
    @Test fun undoRestoresSuppressionAfterAnExpiredBinVideoIsSavedAgain() {
        val initial=LibraryData(channels=listOf(channel),dismissed=setOf(video.id))
        val (saved,undo)=moveVideo(initial,video,1,now)
        val restored=undoVideoMove(saved,undo)
        assertTrue(restored.later.isEmpty());assertTrue(video.id in restored.dismissed)
        assertTrue(visibleHome(restored).isEmpty())
    }
    @Test fun returnToHomeAddsVideoSavedFromChannelAndUndoRestoresWatchLater() {
        val initial=LibraryData(channels=listOf(channel),later=listOf(SavedVideo(video,now)),dismissed=setOf(video.id))
        val (home,undo)=moveVideo(initial,video,0,now)
        assertEquals(listOf(video),visibleHome(home));assertTrue(home.later.isEmpty())
        val restored=undoVideoMove(home,undo)
        assertTrue(visibleHome(restored).isEmpty());assertEquals(initial.later,restored.later)
    }
    @Test fun groupOrderingSurvivesStorageRecoveryAndIndependentRefresh() {
        val initial=LibraryData(groups=setOf("Tech","Health","Finance"),groupOrder=listOf("Tech","Health","Finance"))
        val edited=moveGroup(initial,"Finance",-1)
        assertEquals(listOf("Tech","Finance","Health"),orderedGroups(edited))
        assertEquals(orderedGroups(edited),orderedGroups(LibraryStore.decode(LibraryStore.encode(edited))))
        val merged=mergeLibraryEdit(initial.copy(feed=listOf(video)),initial,edited)
        assertEquals(listOf(video),merged.feed);assertEquals(edited.groupOrder,merged.groupOrder)
        assertEquals(edited.groupOrder,withOrganisation(LibraryData(),edited).groupOrder)
        assertEquals(edited.groupOrder,recoverOrganisation(LibraryData(),edited).groupOrder)
        assertEquals(listOf("Tech","Health"),orderedGroups(edited.copy(groups=edited.groups-"Finance")))
        assertEquals(edited,moveGroup(edited,"Tech",-1))
    }
    @Test fun freshUserKeyUsesGoogleEndpointAndNeverHostedService()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"UC1","snippet":{"title":"Channel"},"contentDetails":{"relatedPlaylists":{"uploads":"UU1"}}}]}"""))
            val api=YoutubeLibraryApi("",OkHttpClient(),server.url("/").toString(),apiKey="test-key")
            assertEquals("UC1",api.channels(listOf("UC1")).single().id)
            val request=server.takeRequest()
            assertEquals("/channels",request.requestUrl!!.encodedPath)
            assertEquals("test-key",request.getHeader("X-Goog-Api-Key"));assertNull(request.getHeader("Authorization"))
            assertFalse(request.path.orEmpty().contains("test-key"))
        }
    }
    @Test fun directSearchEnrichesVideosAndUsesOnlyConfiguredKey()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":{"videoId":"aaaaaaaaaaa"},"snippet":{"title":"Basic","channelId":"UC1"}}],"nextPageToken":"next"}"""))
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"aaaaaaaaaaa","snippet":{"title":"Full","channelId":"UC1"},"contentDetails":{"duration":"PT10M"}}]}"""))
            val page=SearchRepository(OkHttpClient(),{"test-key"},server.url("/").toString()).search("tea",null)
            assertEquals("Full",page.items.single().title);assertEquals("PT10M",page.items.single().duration);assertEquals("next",page.nextPageToken)
            val search=server.takeRequest();assertEquals("tea",search.requestUrl!!.queryParameter("q"));assertEquals("test-key",search.getHeader("X-Goog-Api-Key"))
            assertEquals("/videos",server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun searchQuotaErrorDoesNotEchoKeyOrRawGoogleMessage()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"private-key-value","errors":[{"reason":"quotaExceeded"}]}}"""))
            try {SearchRepository(OkHttpClient(),{"test-key"},server.url("/").toString()).search("tea",null);fail("Expected quota failure")}
            catch(e:SearchFailure){assertEquals("quota_exhausted",e.code);assertFalse(e.message.contains("private-key-value"))}
        }
    }
    @Test fun summaryUsesPublicUrlHighThinkingAndExcludesThoughtParts()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"internal analysis"},{"text":"• An insight."}]},"finishReason":"STOP"}]}"""))
            val summary=SummaryRepository(OkHttpClient(),server.url("/v1beta").toString()).summarize(video.id,"test-gemini-key","models/gemini-flash-latest")
            assertEquals("• An insight.",summary)
            val request=server.takeRequest();assertEquals("/v1beta/models/gemini-flash-latest:generateContent",request.path)
            assertEquals("test-gemini-key",request.getHeader("X-Goog-Api-Key"))
            val body=JSONObject(request.body.readUtf8())
            assertEquals(youtubeWatchUrl(video.id),body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("fileData").getString("fileUri"))
            assertEquals("HIGH",body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
            assertFalse(body.toString().contains("test-gemini-key"))
        }
    }
    @Test fun summaryRejectsInvalidModelsBeforeAnyNetworkRequest()=runBlocking {
        assertTrue(validGeminiModel("models/gemini-3.8-flash"));assertFalse(validGeminiModel("gemini-flash?key=oops"))
        MockWebServer().use {server->
            try {SummaryRepository(OkHttpClient(),server.url("/").toString()).summarize(video.id,"test-key","../secret");fail("Must reject")}
            catch(_:IllegalArgumentException){}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun summaryFailureUsesSafeActionableError()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"secret request detail"}}"""))
            try {SummaryRepository(OkHttpClient(),server.url("/").toString()).summarize(video.id,"test-key","gemini-flash-latest");fail("Must fail")}
            catch(e:SearchFailure){assertTrue(e.message.contains("quota"));assertFalse(e.message.contains("secret request detail"))}
        }
    }
}
