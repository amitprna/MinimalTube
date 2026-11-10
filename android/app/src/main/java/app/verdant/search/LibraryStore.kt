package app.verdant.search

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.Duration

data class ChannelRule(val hideShorts: Boolean = false, val minimumSeconds: Long = 0, val groups: Set<String> = emptySet())
data class SavedVideo(val video: Video, val savedAt: Long)
data class LibraryData(
    val channels: List<Channel> = emptyList(), val feed: List<Video> = emptyList(),
    val later: List<SavedVideo> = emptyList(), val bin: List<SavedVideo> = emptyList(),
    val rules: Map<String,ChannelRule> = emptyMap(), val videoGroups: Map<String,Set<String>> = emptyMap(),
    val groups: Set<String> = emptySet(), val syncedAt: Long = 0, val groupIds: Map<String,String> = emptyMap(), val channelSyncedAt: Map<String,Long> = emptyMap(), val videoSyncedAt: Map<String,Long> = emptyMap(),
    val groupOrder: List<String> = emptyList(), val dismissed: Set<String> = emptySet()
)
fun orderedGroups(data: LibraryData)=(data.groupOrder.filter {it in data.groups}+data.groups.sorted()).distinct()
fun moveGroup(data: LibraryData, name: String, direction: Int): LibraryData {
    val order=orderedGroups(data).toMutableList();val from=order.indexOf(name);val to=from+direction
    if(from<0 || to !in order.indices)return data
    java.util.Collections.swap(order,from,to);return data.copy(groupOrder=order)
}
const val BIN_RETENTION_MS = 24L*60*60*1000
/** Binned videos leave the Bin 24 hours after they were binned; they stay hidden from Home afterwards. */
fun binExpiry(savedAt: Long): Long = savedAt+BIN_RETENTION_MS
fun binRemovalLabel(expiry: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val at=Instant.ofEpochMilli(expiry).atZone(zone);val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time=at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when(at.toLocalDate()) {today->"Removed today at $time";today.plusDays(1)->"Removed tomorrow at $time";else->"Removed "+at.toLocalDate()+" at $time"}
}
const val UNGROUPED = "__ungrouped__"
fun groupMatches(data: LibraryData, group: String?, channelId: String, videoId: String? = null): Boolean {
    if(group==null)return true
    val channelGroups=data.rules[channelId]?.groups.orEmpty()
    val videoGroups=data.videoGroups[videoId].orEmpty()
    return if(group==UNGROUPED)channelGroups.none {it in data.groups} && videoGroups.none {it in data.groups}
        else group in data.groups && (group in channelGroups || group in videoGroups)
}
fun groupCodes(groups: Set<String>, stored: Map<String,String>): Map<String,String> {
    val used=mutableSetOf<String>()
    return groups.sorted().associateWith { name ->
        val base=(stored[name] ?: name).uppercase(java.util.Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }.take(5).ifBlank { "GROUP" }
        var code=base;var n=1
        while(code in used || code in setOf("ALL","UNG")) { val suffix=(n++).toString();code=base.take((5-suffix.length).coerceAtLeast(0))+suffix }
        used+=code;code
    }
}
fun durationMinimum(rule: ChannelRule) = maxOf(rule.minimumSeconds,if(rule.hideShorts)180L else 0L)
fun visibleHome(data: LibraryData, group: String? = null): List<Video> {
    val hidden = (data.later + data.bin).map { it.video.id }.toSet()+data.dismissed
    val subscriptions = data.channels.map { it.id }.toSet()
    return data.feed.filter { v ->
        val rule = data.rules[v.channelId] ?: ChannelRule()
        val seconds = try { Duration.parse(v.duration).seconds } catch (_: Exception) { null }
        v.channelId in subscriptions && v.id !in hidden &&
            (!rule.hideShorts || (seconds != null && seconds > 180) || v.title=="Saved video") &&
            (rule.minimumSeconds == 0L || (seconds != null && seconds > rule.minimumSeconds) || v.title=="Saved video") &&
            groupMatches(data,group,v.channelId,v.id)
    }.distinctBy { it.id }.sortedByDescending { it.publishedAt }
}
fun pruneLibrary(data: LibraryData, now: Long): LibraryData {
    val bin = data.bin.filter { now < binExpiry(it.savedAt) }
    val expiredBinIds=data.bin.filter {now>=binExpiry(it.savedAt)}.map {it.video.id}.toSet()
    fun channelStale(channel: Channel): Boolean { val time=channel.metadataCheckedAt.takeIf {it>0} ?: data.channelSyncedAt[channel.id] ?: data.syncedAt;return time>0 && now-time>=30L*24*3600*1000 }
    fun bare(entry: SavedVideo) = entry.copy(video = entry.video.copy(title="Saved video",channelTitle="",thumbnailUrl="",duration=null,viewCount=null,available=null,publishedAt=""))
    fun pruneSaved(entry: SavedVideo)=if(videoMetadataTime(data,entry.video)>0 && now-videoMetadataTime(data,entry.video)>=30L*24*3600*1000)bare(entry) else entry
    // Videos hidden from Home (saved, binned or dismissed) keep only their ID here: Watch Later and Bin hold
    // their own copies, and Return to Home or Undo puts the video back. This keeps the stored feed small.
    val hidden=limitDismissed(data.dismissed+data.bin.map {it.video.id}+data.later.map {it.video.id})
    val feed=data.feed.filterNot {it.id in hidden}
    val retainedIds=(feed.map {it.id}+data.later.map {it.video.id}+bin.map {it.video.id}).toSet()-expiredBinIds
    return data.copy(bin=bin.map(::pruneSaved), later=data.later.map(::pruneSaved),
        feed=feed.map {if(now-videoMetadataTime(data,it)>=30L*24*3600*1000 && videoMetadataTime(data,it)>0)bare(SavedVideo(it,0)).video else it},channels=data.channels.map { if(channelStale(it))it.copy(title="Channel",thumbnail="",description="") else it },
        videoGroups=data.videoGroups,videoSyncedAt=data.videoSyncedAt.filterKeys {it in retainedIds},dismissed=hidden)
}
const val MAX_DISMISSED_IDS=5000
/** Old dismissed IDs can no longer be rediscovered by a refresh, so only the newest are kept. */
fun limitDismissed(ids: Set<String>, max: Int = MAX_DISMISSED_IDS): Set<String> =
    if(ids.size<=max+max/5) ids else ids.toList().takeLast(max).toSet()

fun withOrganisation(library: LibraryData, organisation: LibraryData?): LibraryData = if(organisation==null)library else library.copy(groups=organisation.groups,groupIds=organisation.groupIds,rules=organisation.rules,videoGroups=organisation.videoGroups,groupOrder=organisation.groupOrder)

class LibraryStore(context: Context, owner: String) {
    private val prefs = context.getSharedPreferences("verdant-library",0)
    private val organisationPrefs=context.getSharedPreferences("verdant-organisation",0)
    private val key = "library_" + java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
    fun load(): LibraryData = synchronized(cache) {
        val current=cache[key] ?: run {
            val library=runCatching {decode(JSONObject(prefs.getString(key,"{}").orEmpty()))}.getOrDefault(LibraryData())
            val organisation=runCatching {organisationPrefs.getString(key,null)?.let {decode(JSONObject(it))}}.getOrNull()
            withOrganisation(library,organisation)
        }
        val now=System.currentTimeMillis()
        if(now-(prunedAt[key] ?: 0) in 0 until 60_000)current
        else pruneLibrary(current,now).also { cache[key]=it;prunedAt[key]=now }
    }
    fun save(data: LibraryData) = synchronized(cache) {
        val now=System.currentTimeMillis()
        val initial=if(data.syncedAt==0L && (data.channels.isNotEmpty() || data.feed.isNotEmpty() || data.later.isNotEmpty() || data.bin.isNotEmpty())) data.copy(syncedAt=now) else data
        val snapshot=pruneLibrary(initial,now)
        if(cache[key]==snapshot)return@synchronized
        cache[key]=snapshot
        prunedAt[key]=now
        // Bursts of edits (for example one per channel during refresh) share a single disk write
        // of the newest snapshot instead of serialising the whole library each time.
        if(pendingWrites.add(key))writes.execute {
            pendingWrites.remove(key)
            val latest=cache[key] ?: return@execute
            // Organisation is durable user data, never part of the disposable image/search cache.
            val organisation=encode(LibraryData(groups=latest.groups,groupIds=latest.groupIds,rules=latest.rules,videoGroups=latest.videoGroups,groupOrder=latest.groupOrder)).toString()
            if(organisationPrefs.getString(key,null)!=organisation)organisationPrefs.edit().putString(key,organisation).commit()
            prefs.edit().putString(key,encode(latest).toString()).commit()
        }
        revision.value=revision.value+1
    }
    fun update(change: (LibraryData)->LibraryData): LibraryData = synchronized(cache) { save(change(load()));load() }
    companion object {
        val revision=kotlinx.coroutines.flow.MutableStateFlow(0L)
        private val cache=java.util.concurrent.ConcurrentHashMap<String,LibraryData>()
        private val prunedAt=java.util.concurrent.ConcurrentHashMap<String,Long>()
        private val writes=java.util.concurrent.Executors.newSingleThreadExecutor()
        private val pendingWrites=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun flush() {writes.submit {}.get()}
        fun cleanup(context: Context) {
            writes.submit {
            val prefs = context.getSharedPreferences("verdant-library",0); val edit = prefs.edit()
            val organisation=context.getSharedPreferences("verdant-organisation",0)
            prefs.all.filterKeys { it.startsWith("library_") }.forEach { (key,value) ->
                try { val data=cache.compute(key) { _,current -> pruneLibrary(current ?: withOrganisation(decode(JSONObject(value as String)),organisation.getString(key,null)?.let {decode(JSONObject(it))}),System.currentTimeMillis()) }!!;edit.putString(key,encode(data).toString()) } catch (_: Exception) { /* preserve malformed storage for recovery */ }
            }
            edit.commit()
            }.get()
        }
        private fun array(values: Iterable<String>) = JSONArray(values.toList())
        private fun strings(a: JSONArray?): Set<String> = if(a==null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()
        fun video(v: Video) = JSONObject().put("id",v.id).put("title",v.title).put("channel",v.channelTitle).put("channelId",v.channelId).put("published",v.publishedAt).put("thumb",v.thumbnailUrl).put("duration",v.duration).put("views",v.viewCount).put("available",v.available)
        private fun readVideo(v: JSONObject) = Video(v.getString("id"),v.optString("title"),v.optString("channel"),v.optString("published"),v.optString("thumb"),v.optString("duration").takeIf { it.isNotBlank() },v.optString("views").takeIf { it.isNotBlank() },if(v.has("available"))v.optBoolean("available") else null,v.optString("channelId"))
        fun encode(d: LibraryData): JSONObject {
            val rules=JSONObject(); d.rules.forEach { (id,r) -> rules.put(id,JSONObject().put("shorts",r.hideShorts).put("min",r.minimumSeconds).put("groups",array(r.groups))) }
            val tags=JSONObject(); d.videoGroups.forEach { (id,g) -> tags.put(id,array(g)) }
            fun saved(items: List<SavedVideo>) = JSONArray(items.map { JSONObject().put("video",video(it.video)).put("time",it.savedAt) })
            return JSONObject().put("channels",JSONArray(d.channels.map { JSONObject().put("id",it.id).put("title",it.title).put("thumb",it.thumbnail).put("uploads",it.uploads).put("description",it.description).put("metadataCheckedAt",it.metadataCheckedAt) }))
                .put("feed",JSONArray(d.feed.map(::video))).put("later",saved(d.later)).put("bin",saved(d.bin)).put("rules",rules).put("tags",tags).put("groups",array(d.groups)).put("synced",d.syncedAt).put("groupIds",JSONObject(groupCodes(d.groups,d.groupIds))).put("channelSyncedAt",JSONObject(d.channelSyncedAt)).put("videoSyncedAt",JSONObject(d.videoSyncedAt)).put("groupOrder",array(d.groupOrder)).put("dismissed",array(d.dismissed))
        }
        fun decode(o: JSONObject): LibraryData {
            fun objects(name: String): List<JSONObject> = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            fun saved(name: String) = objects(name).map { SavedVideo(readVideo(it.getJSONObject("video")),it.getLong("time")) }
            val rules=o.optJSONObject("rules") ?: JSONObject(); val tags=o.optJSONObject("tags") ?: JSONObject()
            val sync=o.optJSONObject("channelSyncedAt") ?: JSONObject()
            val videoSync=o.optJSONObject("videoSyncedAt") ?: JSONObject()
            val groups=strings(o.optJSONArray("groups"));val ids=o.optJSONObject("groupIds") ?: JSONObject()
            return LibraryData(objects("channels").map { Channel(it.getString("id"),it.optString("title"),it.optString("thumb"),it.optString("uploads"),it.optString("description"),it.optLong("metadataCheckedAt")) },objects("feed").map(::readVideo),saved("later"),saved("bin"),
                rules.keys().asSequence().associateWith { id -> val r=rules.getJSONObject(id); ChannelRule(r.optBoolean("shorts"),r.optLong("min"),strings(r.optJSONArray("groups"))) },
                tags.keys().asSequence().associateWith { strings(tags.optJSONArray(it)) },groups,o.optLong("synced"),groupCodes(groups,ids.keys().asSequence().associateWith { ids.getString(it) }),sync.keys().asSequence().associateWith {sync.getLong(it)},videoSync.keys().asSequence().associateWith {videoSync.getLong(it)},strings(o.optJSONArray("groupOrder")).toList(),strings(o.optJSONArray("dismissed")))
        }
    }
}
class BinCleanupWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result { LibraryStore.cleanup(applicationContext); return Result.success() }
}


