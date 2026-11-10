package app.verdant.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Duration

data class Channel(val id: String, val title: String, val thumbnail: String = "", val uploads: String = "", val description: String = "", val metadataCheckedAt: Long = 0)
data class UploadPage(val videos: List<Video>, val nextPage: String?)

class YoutubeLibraryApi(private val token: String, private val client: OkHttpClient = sharedClient, private val baseUrl: String = "https://www.googleapis.com/youtube/v3/", private val minimumRequestIntervalMs: Long = 0, private val apiKey: String = AppSettings.key("youtube")): FeedSource {
    companion object { private val sharedClient=OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).connectTimeout(Duration.ofSeconds(8)).followRedirects(false).build() }
    private val requestGate=Mutex()
    private var lastRequestNanos=0L
    private suspend fun call(path: String, params: Map<String,String>, body: JSONObject? = null, delete: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if(minimumRequestIntervalMs>0)requestGate.withLock {
            if(lastRequestNanos!=0L)delay((minimumRequestIntervalMs-(System.nanoTime()-lastRequestNanos)/1_000_000).coerceAtLeast(0))
            lastRequestNanos=System.nanoTime()
        }
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
        params.forEach { (key,value) -> url.addQueryParameter(key,value) }
        val builder = Request.Builder().url(url.build()).apply {if(token.isNotBlank())header("Authorization","Bearer $token") else {
            if(apiKey.isBlank())throw SearchFailure("configuration","Add a YouTube API key in Settings → API keys.")
            header("X-Goog-Api-Key",apiKey)
        }}
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        if (delete) builder.delete()
        client.newCall(AppSettings.identifyAndroid(builder).build()).awaitResponse().use { response ->
            if (response.code == 204) return@withContext JSONObject()
            val source = response.body?.source() ?: throw SearchFailure("network","YouTube returned no response.")
            if (source.request(2_097_153)) throw SearchFailure("response","YouTube returned too much data.")
            val json = try {JSONObject(source.readUtf8())} catch(e:org.json.JSONException) {
                if(response.code>=500)throw SearchFailure("service_unavailable","YouTube is temporarily unavailable.")
                throw SearchFailure("response","YouTube returned an unreadable response.")
            }
            if (!response.isSuccessful) {
                if(response.code>=500)throw SearchFailure("service_unavailable","YouTube is temporarily unavailable.")
                if(response.code==429)throw SearchFailure("rate_limited","YouTube request rate limit reached; waiting to retry.")
                val reason = json.optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                if(reason in setOf("rateLimitExceeded","userRateLimitExceeded"))throw SearchFailure("rate_limited","YouTube request rate limit reached; waiting to retry.")
                if(reason in setOf("keyInvalid","accessNotConfigured","ipRefererBlocked","forbidden") && token.isBlank())throw SearchFailure("service_configuration","Check your YouTube key, API enablement and restrictions in Settings.")
                val message = when {
                    response.code == 401 -> "YouTube access expired. Import from Google again to reconnect."
                    reason == "quotaExceeded" -> "The YouTube allowance is used up for today. Your saved lists are still available."
                    reason == "subscriptionDuplicate" -> "You already subscribe to this channel. Refresh your subscriptions."
                    reason == "youtubeSignupRequired" -> "This Google account needs a YouTube channel before it can manage subscriptions."
                    response.code == 403 -> "YouTube did not allow this request. Check the requested account permission."
                    else -> "YouTube could not complete this request. Please try again."
                }
                throw SearchFailure(reason ?: "youtube",message)
            }
            json
        }
    }
    suspend fun subscriptions(): List<Channel> {
        val channels = mutableListOf<Channel>(); var page = ""
        do {
            val params = mutableMapOf("part" to "snippet", "mine" to "true", "maxResults" to "50", "order" to "alphabetical")
            if (page.isNotBlank()) params["pageToken"] = page
            val result = call("subscriptions",params)
            val items = result.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val s = items.getJSONObject(i).getJSONObject("snippet")
                channels += Channel(s.getJSONObject("resourceId").getString("channelId"),s.optString("title"),thumb(s))
            }
            page = result.optString("nextPageToken")
        } while (page.isNotBlank())
        return channels.distinctBy { it.id }
    }
    override suspend fun channels(ids: List<String>): List<Channel> {
        val all = mutableListOf<Channel>()
        for (batch in ids.chunked(50)) {
            val result = call("channels",mapOf("part" to "snippet,contentDetails", "id" to batch.joinToString(",")))
            val items = result.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) all += parseChannel(items.getJSONObject(i))
        }
        return all
    }
    suspend fun resolveChannel(input: String): Channel {
        val text = input.trim()
        val identifier = if (text.startsWith("https://")) {
            val url = text.toHttpUrl()
            require(url.host in listOf("youtube.com","www.youtube.com","m.youtube.com")) { "Use a YouTube channel URL, @handle or channel ID." }
            url.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
        } else text
        val params = mutableMapOf("part" to "snippet,contentDetails")
        when {
            identifier.matches(Regex("UC[A-Za-z0-9_-]{22}")) -> params["id"] = identifier
            identifier.startsWith("@") -> params["forHandle"] = identifier
            else -> throw SearchFailure("channel","Enter a channel @handle, /channel/ URL or UC channel ID.")
        }
        val items = call("channels",params).optJSONArray("items")
        if (items == null || items.length() == 0) throw SearchFailure("channel","No channel was found for this address.")
        return parseChannel(items.getJSONObject(0))
    }
    suspend fun uploads(channel: Channel, next: String? = null): UploadPage {
        if (channel.uploads.isBlank()) return UploadPage(emptyList(),null)
        val params = mutableMapOf("part" to "contentDetails", "playlistId" to channel.uploads, "maxResults" to "20")
        next?.let { params["pageToken"] = it }
        val result = call("playlistItems",params)
        val items = result.optJSONArray("items")
        val ids = if (items == null) emptyList() else (0 until items.length()).map { items.getJSONObject(it).getJSONObject("contentDetails").getString("videoId") }
        return UploadPage(videos(ids),result.optString("nextPageToken").takeIf { it.isNotBlank() })
    }
    suspend fun recentUploads(channel: Channel, since: java.time.Instant, until: java.time.Instant): UploadPage =
        UploadPage(videos(recentUploadIds(channel,since,until)).filter {v -> runCatching {java.time.Instant.parse(v.publishedAt).let {!it.isBefore(since) && !it.isAfter(until)}}.getOrDefault(false)},null)

    override suspend fun recentUploadIds(channel: Channel, since: java.time.Instant, until: java.time.Instant): List<String> {
        if(channel.uploads.isBlank())throw SearchFailure("channel","Channel has no uploads playlist.")
        val ids=linkedSetOf<String>();val seenTokens=mutableSetOf<String>();var next: String?=null
        do {
            val params=mutableMapOf("part" to "contentDetails","playlistId" to channel.uploads,"maxResults" to "50")
            next?.let {params["pageToken"]=it}
            val result=call("playlistItems",params)
            val items=result.optJSONArray("items") ?: throw SearchFailure("response","YouTube returned an incomplete upload page.")
            var allOlder=items.length()>0
            for(i in 0 until items.length()) {
                val detail=items.getJSONObject(i).optJSONObject("contentDetails") ?: throw SearchFailure("response","Upload details are missing.")
                val id=detail.optString("videoId").takeIf {it.isNotBlank()} ?: continue
                val published=runCatching {java.time.Instant.parse(detail.optString("videoPublishedAt"))}.getOrNull()
                if(published==null || !published.isBefore(since))allOlder=false
                if(published==null || (!published.isBefore(since) && !published.isAfter(until)))ids+=id
            }
            next=if(allOlder)null else result.optString("nextPageToken").takeIf {it.isNotBlank()}
            if(next!=null && !seenTokens.add(next))throw SearchFailure("pagination","YouTube repeated an upload page. Retry pending.")
        } while(next!=null)
        return ids.toList()
    }
    override suspend fun videos(ids: List<String>): List<Video> {
        val videos = mutableListOf<Video>()
        for (batch in ids.chunked(50)) {
            val items = call("videos",mapOf("part" to "snippet,contentDetails,statistics,status", "id" to batch.joinToString(","))).optJSONArray("items") ?: throw SearchFailure("response","YouTube returned incomplete video details.")
            for (i in 0 until items.length()) {
                val v = items.getJSONObject(i); val s = v.getJSONObject("snippet")
                videos += Video(v.getString("id"),s.optString("title"),s.optString("channelTitle"),s.optString("publishedAt"),thumb(s),v.optJSONObject("contentDetails")?.optString("duration"),v.optJSONObject("statistics")?.optString("viewCount"),v.optJSONObject("status")?.optBoolean("embeddable"),s.optString("channelId"))
            }
        }
        return videos
    }
    /** Returns false when the account already follows the channel on YouTube, which is not a failure. */
    suspend fun subscribe(channelId: String): Boolean {
        require(channelId.matches(Regex("UC[A-Za-z0-9_-]{22}")))
        return try {
            call("subscriptions",mapOf("part" to "snippet"),JSONObject().put("snippet",JSONObject().put("resourceId",JSONObject().put("kind","youtube#channel").put("channelId",channelId))))
            true
        } catch(e: SearchFailure) { if(e.code=="subscriptionDuplicate") false else throw e }
    }
    suspend fun isSubscribed(channelId: String): Boolean = (call("subscriptions",mapOf("part" to "id","mine" to "true","forChannelId" to channelId)).optJSONArray("items")?.length() ?: 0)>0
    suspend fun unsubscribe(channelId: String) {
        require(channelId.matches(Regex("UC[A-Za-z0-9_-]{22}")))
        val items=call("subscriptions",mapOf("part" to "id","mine" to "true","forChannelId" to channelId)).optJSONArray("items") ?: return
        if(items.length()==0) return
        val subscriptionId=items.getJSONObject(0).getString("id")
        try { call("subscriptions",mapOf("id" to subscriptionId),delete=true) }
        catch(e: SearchFailure) { if(e.code!="subscriptionNotFound") throw e }
    }
    private fun parseChannel(v: JSONObject): Channel {
        val s = v.getJSONObject("snippet")
        return Channel(v.getString("id"),s.optString("title"),thumb(s),v.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty(),s.optString("description"),System.currentTimeMillis())
    }
    private fun thumb(s: JSONObject): String = s.optJSONObject("thumbnails")?.let { it.optJSONObject("high") ?: it.optJSONObject("medium") ?: it.optJSONObject("default") }?.optString("url").orEmpty()
}

internal suspend fun okhttp3.Call.awaitResponse(): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object:okhttp3.Callback {
        override fun onFailure(call:okhttp3.Call,e:java.io.IOException) {if(continuation.isActive)continuation.resumeWith(Result.failure(e))}
        override fun onResponse(call:okhttp3.Call,response:okhttp3.Response) {continuation.resume(response,onCancellation={_,value,_->value.close()})}
    })
}

