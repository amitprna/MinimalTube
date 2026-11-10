package app.verdant.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class SummaryRecoveryTest {
    private val video="aaaaaaaaaaa"
    private val model="gemini-flash-latest"
    private val success="""{"candidates":[{"content":{"parts":[{"text":"An insight."}]},"finishReason":"STOP"}]}"""
    private fun repository(server:MockWebServer,wait:suspend (Long)->Unit={})=
        SummaryRepository(OkHttpClient(),server.url("/v1beta").toString(),wait,{0})

    @Test fun unavailableRecoversWithBackoffAndSameRequest()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"status":"UNAVAILABLE","message":"secret detail"}}"""))
            server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
            server.enqueue(MockResponse().setBody(success))
            val waits=mutableListOf<Long>();val progress=mutableListOf<String>()
            assertEquals("An insight.",repository(server){waits+=it}.summarize(video,"test-key",model,progress::add))
            // 503 (overloaded) waits longer than the 502 gateway error that follows.
            assertEquals(listOf(3000L,2000L),waits);assertEquals(3,server.requestCount)
            assertTrue(progress.any {it.contains("Retrying summary (1/2)")})
            val requests=List(3){server.takeRequest()}
            assertEquals(1,requests.map {it.body.readUtf8()}.distinct().size)
            assertEquals(1,requests.map {it.path}.distinct().size)
        }
    }

    @Test fun persistentServerFailureStopsAfterTwoRetriesWithSafeDiagnostics()=runBlocking {
        MockWebServer().use {server->
            repeat(3){server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"status":"UNAVAILABLE","message":"private-key-value"}}"""))}
            try {repository(server).summarize(video,"test-key",model);fail("Expected failure")}
            catch(e:SearchFailure){
                assertEquals("summary_http_503",e.code)
                for(expected in listOf("HTTP 503","UNAVAILABLE","3 attempts",model))assertTrue(e.message.contains(expected))
                assertFalse(e.message.contains("private-key-value"));assertFalse(e.message.contains("test-key"))
            }
            assertEquals(3,server.requestCount)
        }
    }

    @Test fun configurationAndBillingErrorsDoNotRetry()=runBlocking {
        for((code,expected) in mapOf(400 to "model configuration",401 to "key",402 to "billing",403 to "permissions",404 to "not found",501 to "does not support")) {
            MockWebServer().use {server->
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","1").setBody("""{"error":{"status":"a secret token","message":"private-key-value"}}"""))
                try {repository(server){fail("Must not retry HTTP $code")}.summarize(video,"test-key",model);fail("Expected failure")}
                catch(e:SearchFailure){assertTrue(e.message.contains(expected));assertTrue(e.message.contains("HTTP $code"));assertFalse(e.message.contains("private-key-value"));assertFalse(e.message.contains("secret token"))}
                assertEquals(1,server.requestCount)
            }
        }
    }

    @Test fun retryAfterHeaderIsRespected()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","5"))
            server.enqueue(MockResponse().setBody(success))
            val waits=mutableListOf<Long>()
            repository(server){waits+=it}.summarize(video,"test-key",model)
            assertEquals(listOf(5000L),waits)
        }
    }

    @Test fun serverRetryLongerThanThirtySecondsDoesNotKeepUserWaitingOrRetryEarly()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","120"))
            try {repository(server){fail("Must not retry early")}.summarize(video,"test-key",model);fail("Expected failure")}
            catch(e:SearchFailure){assertTrue(e.message.contains("HTTP 503"))}
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun rateLimitWithRetryInfoRecovers()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3.5s"}]}}"""))
            server.enqueue(MockResponse().setBody(success))
            val waits=mutableListOf<Long>()
            assertEquals("An insight.",repository(server){waits+=it}.summarize(video,"test-key",model))
            assertEquals(listOf(3500L),waits)
        }
    }

    @Test fun dailyQuotaDoesNotRetryEvenWhenRetryInfoIsPresent()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}"""))
            try {repository(server){fail("Daily quota cannot recover immediately")}.summarize(video,"test-key",model);fail("Expected failure")}
            catch(e:SearchFailure){assertTrue(e.message.contains("daily quota"))}
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun cancelDuringBackoffDoesNotSendAnotherRequest()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(503))
            val waiting=CompletableDeferred<Unit>()
            val job=launch {repository(server){waiting.complete(Unit);awaitCancellation()}.summarize(video,"test-key",model)}
            waiting.await();job.cancelAndJoin()
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun clientTimeoutIsReportedSeparatelyWithoutAmbiguousGenerationRetry()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(success).setHeadersDelay(500,TimeUnit.MILLISECONDS))
            val client=OkHttpClient.Builder().callTimeout(100,TimeUnit.MILLISECONDS).build()
            try {SummaryRepository(client,server.url("/v1beta").toString()).summarize(video,"test-key",model);fail("Expected timeout")}
            catch(e:SearchFailure){assertEquals("summary_timeout",e.code);assertTrue(e.message.contains(model))}
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun knownRestrictionIsActionableWithoutLeakingRemoteMetadata() {
        val error=summaryApiError(403,"""{"error":{"status":"PERMISSION_DENIED","message":"private-key-value","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_ANDROID_APP_BLOCKED","metadata":{"consumer":"private project"}}]}}""",null)
        val message=error.toSearchFailure(model,1).message
        assertTrue(message.contains("application restrictions"));assertTrue(message.contains("API_KEY_ANDROID_APP_BLOCKED"))
        assertFalse(message.contains("private project"));assertFalse(message.contains("private-key-value"))
    }

    @Test fun depletedPrepaidCreditsAreBillingEvenWhenGoogleStatusSaysResourceExhausted()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"status":"RESOURCE_EXHAUSTED","message":"Your prepayment credits are depleted."}}"""))
            try {repository(server){fail("Do not retry depleted credits")}.summarize(video,"test-key",model);fail("Expected billing failure")}
            catch(e:SearchFailure){
                assertTrue(e.message.contains("billing or additional credits"));assertTrue(e.message.contains("HTTP 402"))
                assertFalse(e.message.contains("temporarily unavailable"));assertFalse(e.message.contains("daily quota"))
            }
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun retryAfterDateAndMalformedResponsesAreHandledSafely() {
        val now=Instant.parse("2026-09-30T10:00:00Z")
        assertEquals(10_000L,summaryApiError(503,"","Wed, 30 Sep 2026 10:00:10 GMT",now).retryDelayMillis)
        for(header in listOf("NaN","Infinity","-1","not a date"))assertNull(summaryApiError(503,"<html>secret</html>",header,now).retryDelayMillis)
        assertFalse(summaryApiError(429,"<html>secret</html>",null).retryable)
    }

    @Test fun contentBlocksAreNotReportedAsAnOutageOrReturnedAsPartialSummaries() {
        for(body in listOf("""{"promptFeedback":{"blockReason":"SAFETY"}}""","""{"candidates":[{"content":{"parts":[{"text":"Partial"}]},"finishReason":"RECITATION"}]}""")) {
            try {summaryText(JSONObject(body));fail("Expected content restriction")}
            catch(e:SearchFailure){assertEquals("summary_blocked",e.code);assertTrue(e.message.contains("content restrictions"))}
        }
    }

    @Test fun overloadedMainModelFallsBackOnceToTheBackupModel()=runBlocking {
        MockWebServer().use {server->
            repeat(2){server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"status":"UNAVAILABLE"}}"""))}
            server.enqueue(MockResponse().setBody(success))
            val progress=mutableListOf<String>()
            val (text,used)=summarizeWithFallback(repository(server),video,"test-key",model,"gemini-flash-lite-latest",progress::add)
            assertEquals("An insight.",text);assertEquals("gemini-flash-lite-latest",used)
            // One retry on the busy main model, then the backup.
            val paths=List(3){server.takeRequest().path.orEmpty()}
            assertTrue(paths.take(2).all {it.contains("/models/gemini-flash-latest:")})
            assertTrue(paths.last().contains("/models/gemini-flash-lite-latest:"))
            assertTrue(progress.any {it.contains("trying gemini-flash-lite-latest")})
        }
    }
    @Test fun backupModelRetriesWithoutThinkingWhenItRejectsTheSetting()=runBlocking {
        MockWebServer().use {server->
            repeat(2){server.enqueue(MockResponse().setResponseCode(503))}
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"status":"INVALID_ARGUMENT"}}"""))
            server.enqueue(MockResponse().setBody(success))
            assertEquals("gemini-flash-lite-latest",summarizeWithFallback(repository(server),video,"test-key",model,"gemini-flash-lite-latest").second)
            val bodies=List(4){JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("generationConfig")}
            assertTrue(bodies[2].has("thinkingConfig"));assertFalse(bodies[3].has("thinkingConfig"))
        }
    }
    @Test fun noFallbackForKeyBillingOrMissingBackup()=runBlocking {
        for((code,fallback) in listOf(402 to "gemini-flash-lite-latest",401 to "gemini-flash-lite-latest",503 to "",503 to model)) {
            MockWebServer().use {server->
                repeat(3){server.enqueue(MockResponse().setResponseCode(code))}
                try {summarizeWithFallback(repository(server),video,"test-key",model,fallback);fail("Expected failure")}
                catch(e:SearchFailure){assertEquals("summary_http_$code",e.code)}
                // Without a usable backup a busy main model keeps its full three attempts.
                assertEquals(if(code==503)3 else 1,server.requestCount)
            }
        }
    }
    @Test fun slowMainModelFallsBackToTheBackupInsteadOfFailing()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(success).setHeadersDelay(2,TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody(success))
            val progress=mutableListOf<String>()
            val (text,used)=summarizeWithFallback(repository(server),video,"test-key",model,"gemini-flash-lite-latest",progress::add,mainBudgetMillis=150)
            assertEquals("An insight.",text);assertEquals("gemini-flash-lite-latest",used)
            assertTrue(progress.any {it.contains("took too long · trying gemini-flash-lite-latest")})
        }
    }
    @Test fun slowMainModelWithoutBackupReportsTheTimeout()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(success).setHeadersDelay(2,TimeUnit.SECONDS))
            try {summarizeWithFallback(repository(server),video,"test-key",model,"",mainBudgetMillis=150);fail("Expected timeout")}
            catch(e:SearchFailure){assertEquals("summary_timeout",e.code)}
        }
    }
}
