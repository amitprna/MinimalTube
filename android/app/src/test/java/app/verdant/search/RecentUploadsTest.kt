package app.verdant.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RecentUploadsTest {
    @Test fun configurationErrorsAreNotDisguisedAsTransientNetworkFailures()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"keyInvalid"}]}}"""))
            val api=YoutubeLibraryApi("",OkHttpClient(),server.url("/").toString(),apiKey="test-key")
            try {retrySyncRead {api.videos(listOf("aaaaaaaaaaa"))};fail("Expected configuration error")}
            catch(e:SearchFailure){assertEquals("service_configuration",e.code)}
            assertEquals(1,server.requestCount)
        }
    }
    private val now=Instant.parse("2026-09-23T12:00:00Z")
    private val channel=Channel("UC1","News",uploads="UU1")
    private fun id(n:Int)="v"+n.toString().padStart(10,'0')
    private fun playlist(values:List<Pair<Int,Instant>>,next:String?=null):String = JSONObject().put("items",JSONArray(values.map {(n,date)->JSONObject().put("contentDetails",JSONObject().put("videoId",id(n)).put("videoPublishedAt",date.toString()))})).apply {if(next!=null)put("nextPageToken",next)}.toString()
    private fun details(values:List<Pair<Int,Instant>>):String=JSONObject().put("items",JSONArray(values.map {(n,date)->JSONObject().put("id",id(n)).put("snippet",JSONObject().put("title","Video $n").put("channelId",channel.id).put("publishedAt",date.toString())).put("contentDetails",JSONObject().put("duration","PT10M"))})).toString()
    @Test fun newsChannelLoadsAllThirtyDailyVideosAcrossPagesAndStopsAtOlderBoundary()=runBlocking {
        MockWebServer().use {server->
            val recent=(1..30).map {it to now.minusSeconds(it*60L)}
            val old=(31..40).map {it to now.minusSeconds(90000)}
            server.enqueue(MockResponse().setBody(playlist(recent.take(20),"page2")))
            server.enqueue(MockResponse().setBody(playlist(recent.drop(20)+old,"older")))
            server.enqueue(MockResponse().setBody(playlist(old,"must-not-fetch")))
            server.enqueue(MockResponse().setBody(details(recent)))
            val api=YoutubeLibraryApi("token",OkHttpClient(),baseUrl=server.url("/").toString())
            assertEquals(30,api.recentUploads(channel,now.minusSeconds(86400),now).videos.size)
            assertEquals(4,server.requestCount)
            assertEquals("/playlistItems",server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("page2",server.takeRequest().requestUrl!!.queryParameter("pageToken"))
            assertEquals("older",server.takeRequest().requestUrl!!.queryParameter("pageToken"))
            val enriched=server.takeRequest().requestUrl!!.queryParameter("id")!!.split(',')
            assertEquals(recent.map {id(it.first)},enriched)
        }
    }
    @Test fun inactiveWeeklyChannelUsesOnlyOneCheckAndNoOldVideoDetails()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(playlist(listOf(1 to now.minusSeconds(7*86400)),"old")))
            val api=YoutubeLibraryApi("token",OkHttpClient(),baseUrl=server.url("/").toString())
            assertTrue(api.recentUploads(channel,now.minusSeconds(86400),now).videos.isEmpty())
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun includesExactDayBoundaryButExcludesFutureAndOlderVideos()=runBlocking {
        MockWebServer().use {server->
            val boundary=1 to now.minusSeconds(86400)
            server.enqueue(MockResponse().setBody(playlist(listOf(2 to now.plusSeconds(100),boundary,3 to now.minusSeconds(86401)))))
            server.enqueue(MockResponse().setBody(details(listOf(boundary))))
            val api=YoutubeLibraryApi("token",OkHttpClient(),baseUrl=server.url("/").toString())
            assertEquals(listOf(id(1)),api.recentUploads(channel,now.minusSeconds(86400),now).videos.map {it.id})
            assertEquals(2,server.requestCount)
        }
    }
}
