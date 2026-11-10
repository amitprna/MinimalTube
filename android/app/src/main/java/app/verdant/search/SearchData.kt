package app.verdant.search

import java.io.IOException
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Video(
    val id: String, val title: String, val channelTitle: String, val publishedAt: String,
    val thumbnailUrl: String, val duration: String?, val viewCount: String?, val available: Boolean?, val channelId: String = ""
)
data class SearchPage(val items: List<Video>, val nextPageToken: String?)
data class SignedInUser(val name: String, val email: String)
object SignInSession { @Volatile var user: SignedInUser? = null }
class SearchFailure(val code: String, override val message: String) : IOException(message)

interface SearchSource {
    suspend fun search(query: String, pageToken: String?): SearchPage
}
class SearchRepository(private val client: OkHttpClient = sharedClient, private val keyProvider: ()->String = {AppSettings.key("youtube")}, private val youtubeBase: String = "https://www.googleapis.com/youtube/v3/") : SearchSource {
    companion object {
        private val sharedClient=OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30))
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(false).followSslRedirects(false).build()
    }
    override suspend fun search(query: String, pageToken: String?): SearchPage {
        val apiKey=keyProvider()
        if(apiKey.isBlank())throw SearchFailure("configuration","Add a YouTube API key in Settings → API keys.")
        return directSearch(apiKey,query,pageToken)
    }
    private suspend fun directSearch(key: String,query: String,page: String?): SearchPage {
        val url="${youtubeBase}search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("part","snippet").addQueryParameter("type","video").addQueryParameter("maxResults","20")
            .addQueryParameter("q",query).addQueryParameter("videoEmbeddable","true").addQueryParameter("videoSyndicated","true").addQueryParameter("safeSearch","moderate")
        page?.let {url.addQueryParameter("pageToken",it)}
        val json=request(AppSettings.identifyAndroid(Request.Builder().url(url.build()).header("X-Goog-Api-Key",key)).build())
        val items=json.optJSONArray("items") ?: throw SearchFailure("response","YouTube returned no results list.")
        val basic=(0 until items.length()).mapNotNull {i ->
            val item=items.getJSONObject(i);val id=item.optJSONObject("id")?.optString("videoId").orEmpty()
            if(!validVideoId(id))return@mapNotNull null
            val s=item.getJSONObject("snippet")
            Video(id,s.optString("title"),s.optString("channelTitle"),s.optString("publishedAt"),s.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url").orEmpty(),null,null,null,s.optString("channelId"))
        }
        val details=try {YoutubeLibraryApi("",client,baseUrl=youtubeBase,apiKey=key).videos(basic.map {it.id}).associateBy {it.id}}catch(e:CancellationException){throw e}catch(_:Exception){emptyMap()}
        return SearchPage(basic.map {details[it.id] ?: it},json.optionalString("nextPageToken"))
    }
    private suspend fun request(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(SearchFailure("network", "Could not connect. Check your internet connection and try again."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val source = response.body?.source() ?: throw IOException("Empty response")
                        if (source.request(1_048_577)) throw IOException("Response too large")
                        val json = JSONObject(source.readUtf8())
                        if (!response.isSuccessful) {
                            val error = json.optJSONObject("error")
                            if(request.header("X-Goog-Api-Key")!=null) {
                                val reason=error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                                throw when {
                                    reason in setOf("quotaExceeded","dailyLimitExceeded") -> SearchFailure("quota_exhausted","Your YouTube project’s daily allowance is used up. Check its quota in Google Cloud.")
                                    response.code==429 || reason in setOf("rateLimitExceeded","userRateLimitExceeded") -> SearchFailure("rate_limited","YouTube is receiving requests too quickly. Try again shortly.")
                                    response.code in setOf(400,401,403) -> SearchFailure("configuration","Check your YouTube key, API enablement and key restrictions in Settings.")
                                    else -> SearchFailure("service","YouTube is temporarily unavailable. Try again later.")
                                }
                            }
                            throw SearchFailure(error?.optString("code") ?: "service", error?.optString("message")?.take(400) ?: "The service is temporarily unavailable.")
                        }
                        if (continuation.isActive) continuation.resume(json)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(if (e is SearchFailure) e else SearchFailure("response", "The service returned an unreadable response."))
                    }
                }
            }
        })
    }
}
private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun durationLabel(value: String?): String? = try {
    if (value == null) null else {
        val seconds = Duration.parse(value).seconds
        if (seconds == 0L) null else if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
} catch (_: Exception) { null }

fun viewCountLabel(value: String?): String? = value?.toLongOrNull()?.let { count ->
    when {
        count >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1fB views", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM views", count / 1_000_000.0)
        count >= 1000 -> String.format(Locale.getDefault(), "%.1fK views", count / 1000.0)
        else -> "$count views"
    }
}


/** Short, human publication age for cards: "Just now", "5 min ago", "3 h ago", "Yesterday", "4 d ago", then a date. */
fun relativeTimeLabel(publishedAt: String, now: java.time.Instant = java.time.Instant.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    val published = runCatching { java.time.Instant.parse(publishedAt) }.getOrNull() ?: return null
    val seconds = Duration.between(published, now).seconds
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        seconds < 2 * 86_400 -> "Yesterday"
        seconds < 7 * 86_400 -> "${seconds / 86_400} d ago"
        else -> {
            val date = published.atZone(zone).toLocalDate()
            val pattern = if (date.year == now.atZone(zone).year) "d MMM" else "d MMM yyyy"
            date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        }
    }
}
