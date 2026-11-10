package app.verdant.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class VideoMove(val video: Video, val previousLater: SavedVideo?, val previousBin: SavedVideo?, val destination: Int, val previousDismissed: Boolean = false)
fun selectTabGroup(selections: Map<Int,String>, tab: Int, group: String?): Map<Int,String> {
    require(tab in 0..3)
    return if(group==null)selections-tab else selections+(tab to group)
}
/** Never fall back to a local mutation when an account operation fails. */
suspend fun changeSubscription(connected: Boolean, remoteChange: suspend ()->Unit, applyLocal: ()->Unit) {
    if(connected)remoteChange()
    applyLocal()
}
fun videoMetadataTime(data: LibraryData, video: Video): Long = data.videoSyncedAt[video.id] ?: data.channelSyncedAt[video.channelId] ?: data.syncedAt
fun mergeRecentUploads(data: LibraryData, channel: Channel, videos: List<Video>, now: Long): LibraryData {
    if(data.channels.none {it.id==channel.id})return data
    val timestamps=(data.feed+data.later.map {it.video}+data.bin.map {it.video}).associate {it.id to videoMetadataTime(data,it)}+data.videoSyncedAt+videos.associate {it.id to now}
    return data.copy(channels=data.channels.map {if(it.id==channel.id)channel else it},
        feed=if(videos.isEmpty())data.feed else (videos+data.feed).distinctBy {it.id}.sortedByDescending {it.publishedAt},
        channelSyncedAt=data.channelSyncedAt+(channel.id to now),videoSyncedAt=timestamps)
}
suspend fun refreshRecentChannel(remote: YoutubeLibraryApi, channel: Channel, data: LibraryData, now: java.time.Instant): UploadPage {
    val recent=remote.recentUploads(channel,now.minusSeconds(24*3600),now)
    // Revalidate retained metadata only when nearly 30 days old; do not discover older uploads.
    val hidden=(data.later+data.bin).map {it.video.id}.toSet()
    val stale=data.feed.filter {it.channelId==channel.id && it.id !in hidden && (videoMetadataTime(data,it)==0L || now.toEpochMilli()-videoMetadataTime(data,it)>=29L*24*3600*1000)}.map {it.id}.distinct()-recent.videos.map {it.id}.toSet()
    return UploadPage(recent.videos+remote.videos(stale),null)
}
/** A completed Google import is authoritative for subscriptions, not local organisation. */
fun reconcileSubscriptions(data: LibraryData, channels: List<Channel>): LibraryData {
    val ids=channels.map { it.id }.toSet()
    return data.copy(channels=channels.distinctBy { it.id },feed=data.feed.filter { it.channelId in ids },channelSyncedAt=data.channelSyncedAt.filterKeys { it in ids })
}
/** Adds channels followed on YouTube since the last import. Nothing is removed. */
fun addSubscriptions(data: LibraryData, channels: List<Channel>): LibraryData {
    val known=data.channels.map { it.id }.toSet()
    val added=channels.filter { it.id.isNotBlank() && it.id !in known }.distinctBy { it.id }
    return if(added.isEmpty()) data else data.copy(channels=data.channels+added)
}
fun recoverOrganisation(current: LibraryData, previous: LibraryData): LibraryData {
    val groups=current.groups+previous.groups
    val rules=(previous.rules.keys+current.rules.keys).associateWith { id ->
        val rule=current.rules[id] ?: previous.rules.getValue(id)
        rule.copy(groups=rule.groups+previous.rules[id]?.groups.orEmpty())
    }
    return current.copy(groups=groups,groupIds=groupCodes(groups,previous.groupIds+current.groupIds),rules=rules,
        groupOrder=(orderedGroups(current)+orderedGroups(previous)).distinct())
}
/** Preserve fields refreshed by background work while applying an unrelated UI edit. */
fun mergeLibraryEdit(latest: LibraryData, before: LibraryData, edited: LibraryData): LibraryData = edited.copy(
    channels=if(before.channels==edited.channels)latest.channels else edited.channels,
    feed=if(before.feed==edited.feed)latest.feed else {
        val previous=before.feed.associateBy {it.id}
        val removed=previous.keys-edited.feed.map {it.id}.toSet()
        val changed=edited.feed.filter {previous[it.id]!=it}.associateBy {it.id}
        val removedChannels=before.channels.map {it.id}.toSet()-edited.channels.map {it.id}.toSet()
        (latest.feed.filterNot {it.id in removed || it.id in changed || it.channelId in removedChannels}+changed.values).distinctBy {it.id}
    },
    channelSyncedAt=if(before.channelSyncedAt==edited.channelSyncedAt)latest.channelSyncedAt else latest.channelSyncedAt+edited.channelSyncedAt,
    videoSyncedAt=latest.videoSyncedAt+edited.videoSyncedAt.filter { (id,time)->before.videoSyncedAt[id]!=time },
    groups=if(before.groups==edited.groups)latest.groups else edited.groups,
    groupOrder=if(before.groupOrder==edited.groupOrder)latest.groupOrder else edited.groupOrder,
    dismissed=(latest.dismissed-(before.dismissed-edited.dismissed))+(edited.dismissed-before.dismissed),
    groupIds=if(before.groupIds==edited.groupIds)latest.groupIds else edited.groupIds,
    rules=if(before.rules==edited.rules)latest.rules else edited.rules,
    later=if(before.later==edited.later)latest.later else edited.later,
    bin=if(before.bin==edited.bin)latest.bin else edited.bin
)
fun moveVideo(data: LibraryData, video: Video, destination: Int, now: Long): Pair<LibraryData,VideoMove> {
    require(destination in 0..2)
    val undo=VideoMove(video,data.later.find {it.video.id==video.id},data.bin.find {it.video.id==video.id},destination,video.id in data.dismissed)
    val entry=SavedVideo(video,now)
    val alreadySaved=data.feed.any {it.id==video.id} || data.later.any {it.video.id==video.id} || data.bin.any {it.video.id==video.id}
    val checked=if(alreadySaved)videoMetadataTime(data,video).takeIf {it>0} ?: now else now
    return data.copy(feed=if(destination==0 && data.feed.none {it.id==video.id})data.feed+video else data.feed,later=data.later.filterNot {it.video.id==video.id}+(if(destination==1)listOf(entry) else emptyList()),bin=data.bin.filterNot {it.video.id==video.id}+(if(destination==2)listOf(entry) else emptyList()),videoSyncedAt=data.videoSyncedAt+(video.id to checked),dismissed=if(destination==0)data.dismissed-video.id else data.dismissed+video.id) to undo
}
fun undoVideoMove(data: LibraryData, move: VideoMove): LibraryData = data.copy(
    dismissed=if(move.previousDismissed || move.previousLater!=null || move.previousBin!=null)data.dismissed+move.video.id else data.dismissed-move.video.id,
    later=data.later.filterNot {it.video.id==move.video.id}+listOfNotNull(move.previousLater),
    bin=data.bin.filterNot {it.video.id==move.video.id}+listOfNotNull(move.previousBin),
    feed=if(data.feed.any {it.id==move.video.id})data.feed else data.feed+move.video
)
suspend fun refreshChannelBatch(channels: List<Channel>, load: suspend (Channel)->UploadPage, apply: (Channel,UploadPage)->Unit): Int = coroutineScope {
    var failures=0
    for(chunk in channels.chunked(3)) {
        val results=chunk.map {channel->async {
            try { val page=load(channel);apply(channel,page);true }
            catch(e:CancellationException){throw e}
            catch(_:Exception){false}
        }}.awaitAll()
        failures+=results.count {!it}
    }
    failures
}

