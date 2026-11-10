package app.verdant.search

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

interface FeedSource {
    suspend fun channels(ids: List<String>): List<Channel>
    suspend fun recentUploadIds(channel: Channel, since: Instant, until: Instant): List<String>
    suspend fun videos(ids: List<String>): List<Video>
}

fun syncSince(lastSuccess: Long, target: Long): Instant = Instant.ofEpochMilli(
    if(lastSuccess<=0)target-86_400_000L else minOf(target,lastSuccess)-7_200_000L)

fun syncProblem(error: Exception): String = when {
    error is SearchFailure && error.code in setOf("quota_exhausted","quotaExceeded") -> "Daily API allowance reached; waiting to retry."
    error is SearchFailure && error.code=="rate_limited" -> "Service rate limit; waiting to retry."
    error is SearchFailure && error.code=="channel" -> "Channel unavailable or no uploads playlist."
    error is java.net.SocketTimeoutException -> "Connection timed out; retry pending."
    error is SearchFailure -> error.message
    error is java.io.IOException -> "Network connection failed; retry pending."
    else -> "Unexpected response; retry pending."
}
fun globalSyncFailure(error: Exception)=error is SearchFailure && error.code in setOf("quota_exhausted","quotaExceeded","rate_limited","service_configuration")

suspend fun <T> retrySyncRead(read: suspend ()->T): T {
    repeat(3) { attempt ->
        try {return read()}
        catch(e:CancellationException){throw e}
        catch(e:Exception) {
            val transient=e is java.io.IOException && (e !is SearchFailure || e.code in setOf("service_unavailable","youtube_unavailable","upstream_error","upstream_timeout","network","service"))
            if(!transient || attempt==2)throw e
            delay(if(attempt==0)1_000 else 3_000)
        }
    }
    error("Unreachable")
}

/** Success checkpoints are applied only after discovery and required enrichment both succeed. */
class FeedSync(private val remote: FeedSource) {
    suspend fun run(target: Long, snapshot: ()->LibraryData,
                    success: (Channel,List<Video>)->Unit, failure: (Channel,Exception)->Unit) = coroutineScope {
        val initial=snapshot()
        val pending=initial.channels.filter {(initial.channelSyncedAt[it.id] ?: 0)<target}
            .sortedBy {initial.channelSyncedAt[it.id] ?: 0}
        val gate=Semaphore(3)
        val blocked=AtomicReference<Exception?>(null)
        val metadata=pending.associate { channel -> channel.id to channel.copy(metadataCheckedAt=channel.metadataCheckedAt.takeIf {it>0} ?: initial.channelSyncedAt[channel.id] ?: initial.syncedAt) }.toMutableMap()
        val metadataErrors=mutableMapOf<String,Exception>()
        // Refresh channel metadata in API batches, instead of one request per channel.
        for(batch in metadata.values.filter {it.uploads.isBlank() || target-it.metadataCheckedAt>=29L*86_400_000}.chunked(50)) {
            try {
                blocked.get()?.let {throw it}
                val loaded=retrySyncRead {remote.channels(batch.map {it.id})}.associateBy {it.id}
                batch.forEach {channel ->
                    val full=loaded[channel.id]
                    if(full==null || full.uploads.isBlank())metadataErrors[channel.id]=SearchFailure("channel","Channel unavailable or no uploads playlist.")
                    else metadata[channel.id]=full
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(globalSyncFailure(e))blocked.compareAndSet(null,e);batch.forEach {metadataErrors[it.id]=e}}
        }
        for(batch in pending.chunked(8)) {
            ensureActive()
            val discoveries=batch.map {channel->async {
                try {
                    blocked.get()?.let {throw it}
                    gate.withPermit {
                        blocked.get()?.let {throw it}
                        val data=snapshot()
                        metadataErrors[channel.id]?.let {throw it}
                        val full=metadata.getValue(channel.id)
                        val since=syncSince(data.channelSyncedAt[channel.id] ?: 0,target)
                        val ids=retrySyncRead {remote.recentUploadIds(full,since,Instant.ofEpochMilli(target))}
                        Triple(full,ids,since)
                    }
                }catch(e:CancellationException){throw e}
                catch(e:Exception){if(globalSyncFailure(e))blocked.compareAndSet(null,e);failure(channel,e);null}
            }}.awaitAll().filterNotNull()
            val data=snapshot()
            val cached=(data.feed+data.later.map {it.video}+data.bin.map {it.video}).associateBy {it.id}
            val fresh=cached.filterValues {it.publishedAt.isNotBlank() && it.available!=false && target-videoMetadataTime(data,it)<29L*86_400_000 && videoMetadataTime(data,it)>0}.keys
            val channelIds=discoveries.map {it.first.id}.toSet()
            val hidden=(data.later+data.bin).map {it.video.id}.toSet()+data.dismissed
            val stale=data.feed.filter {it.channelId in channelIds && it.id !in hidden && it.id !in fresh}.map {it.id}
            val wanted=(discoveries.flatMap {it.second}.filterNot {it in fresh || it in hidden}+stale).distinct()
            val fetched=mutableMapOf<String,Video>();val failedIds=mutableMapOf<String,Exception>()
            for(ids in wanted.chunked(50)) {
                try {blocked.get()?.let {throw it};retrySyncRead {remote.videos(ids)}.forEach {fetched[it.id]=it}}
                catch(e:CancellationException){throw e}
                catch(e:Exception){if(globalSyncFailure(e))blocked.compareAndSet(null,e);ids.forEach {failedIds[it]=e}}
            }
            for((channel,ids,since) in discoveries) {
                val required=(ids+stale.filter {cached[it]?.channelId==channel.id}).toSet()
                val problem=required.firstNotNullOfOrNull {failedIds[it]}
                if(problem!=null){failure(channel,problem);continue}
                // A successful videos.list may omit deleted/private IDs. They must not
                // discard accessible uploads or hold a channel in an endless retry loop.
                // IDs still in the rolling discovery window are checked on later runs.
                // Cached entries already live in the library. Only merge fresh responses.
                val updates=required.mapNotNull {fetched[it] ?: cached[it]?.takeIf {it.id !in fresh}?.copy(title="Saved video",channelTitle="",thumbnailUrl="",duration=null,viewCount=null,publishedAt="",available=false)}.filter {v ->
                    if(v.id in stale)true else runCatching {Instant.parse(v.publishedAt).let {!it.isBefore(since) && !it.isAfter(Instant.ofEpochMilli(target))}}.getOrDefault(false)
                }
                success(channel,updates)
            }
        }
    }
}

