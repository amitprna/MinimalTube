package app.verdant.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTest {
    private val channel="UC"+"a".repeat(22)
    @Test fun unsubscribeDeletesSubscriptionResourceAndAcceptsNoContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"subscription-resource"}]}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            YoutubeLibraryApi("test-token",OkHttpClient(),server.url("/").toString()).unsubscribe(channel)
            val lookup=server.takeRequest()
            assertEquals("GET",lookup.method)
            assertEquals(channel,lookup.requestUrl!!.queryParameter("forChannelId"))
            assertEquals("true",lookup.requestUrl!!.queryParameter("mine"))
            val deletion=server.takeRequest()
            assertEquals("DELETE",deletion.method)
            assertEquals("subscription-resource",deletion.requestUrl!!.queryParameter("id"))
            assertEquals("Bearer test-token",deletion.getHeader("Authorization"))
            assertEquals(0L,deletion.bodySize)
        }
    }
    @Test fun missingSubscriptionDoesNotIssueDeletion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            YoutubeLibraryApi("test-token",OkHttpClient(),server.url("/").toString()).unsubscribe(channel)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun permissionFailureIsReportedInsteadOfClaimingSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"subscription-resource"}]}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"subscriptionForbidden"}]}}"""))
            try {YoutubeLibraryApi("test-token",OkHttpClient(),server.url("/").toString()).unsubscribe(channel);fail("Expected permission error")}
            catch(e:SearchFailure){assertEquals("subscriptionForbidden",e.code)}
        }
    }
    @Test fun subscribingToAChannelAlreadyFollowedOnYouTubeIsNotAnError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"errors":[{"reason":"subscriptionDuplicate"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"id":"new-subscription"}"""))
            val api=YoutubeLibraryApi("test-token",OkHttpClient(),server.url("/").toString())
            assertFalse(api.subscribe(channel))
            assertTrue(api.subscribe(channel))
        }
    }
    @Test fun otherSubscribeFailuresAreStillReported() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"subscriptionForbidden"}]}}"""))
            try {YoutubeLibraryApi("test-token",OkHttpClient(),server.url("/").toString()).subscribe(channel);fail("Expected failure")}
            catch(e:SearchFailure){assertEquals("subscriptionForbidden",e.code)}
        }
    }
    @Test fun dailySyncAddsNewYouTubeChannelsAndRemovesNothing() {
        val local=Channel("UClocal","Local only");val shared=Channel("UCshared","Shared","thumb","UUshared")
        val data=LibraryData(channels=listOf(local,shared),rules=mapOf("UClocal" to ChannelRule(groups=setOf("Tech"))),groups=setOf("Tech"))
        val synced=addSubscriptions(data,listOf(Channel("UCshared","Renamed"),Channel("UCnew","New"),Channel("UCnew","Duplicate"),Channel("","Broken")))
        assertEquals(listOf(local,shared,Channel("UCnew","New")),synced.channels)
        assertEquals(data.rules,synced.rules)
        assertSame(data,addSubscriptions(data,listOf(shared)))
    }
}
