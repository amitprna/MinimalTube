package app.verdant.search

import java.net.URI
import java.net.URLDecoder

internal data class SummaryTimestamp(val start: Int, val end: Int, val seconds: Int)
private val timestampPattern=Regex("(?<![\\p{L}\\p{N}:./])(?:[0-9]{1,3}:[0-5][0-9]:[0-5][0-9]|[0-9]{1,3}:[0-5][0-9])(?![\\p{L}\\p{N}:])")

internal fun timestampSeconds(value: String): Int? {
    if(!value.matches(Regex("(?:[0-9]{1,3}:[0-5][0-9]:[0-5][0-9]|[0-9]{1,3}:[0-5][0-9])")))return null
    return value.split(':').fold(0){total,part->total*60+part.toInt()}
}
internal fun summaryTimestamps(text: String): List<SummaryTimestamp> {
    val urls=Regex("https?://\\S+").findAll(text).map {it.range}.toList()
    return timestampPattern.findAll(text).filter {cue->urls.none {cue.range.first in it}}
        .map {SummaryTimestamp(it.range.first,it.range.last+1,checkNotNull(timestampSeconds(it.value)))}.toList()
}

internal fun urlTimeSeconds(value: String): Int? {
    timestampSeconds(value)?.let {return it}
    if(value.matches(Regex("[0-9]+s?")))return value.removeSuffix("s").toIntOrNull()
    val match=Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matchEntire(value) ?: return null
    if(value.isEmpty())return null
    val hours=match.groupValues[1].ifEmpty {"0"}.toLongOrNull() ?: return null
    val minutes=match.groupValues[2].ifEmpty {"0"}.toLongOrNull() ?: return null
    val seconds=match.groupValues[3].ifEmpty {"0"}.toLongOrNull() ?: return null
    // Bound components before multiplying untrusted URL values.
    if(hours>Int.MAX_VALUE/3600 || minutes>Int.MAX_VALUE/60 || seconds>Int.MAX_VALUE)return null
    return (hours*3600+minutes*60+seconds).takeIf {it<=Int.MAX_VALUE}?.toInt()
}

/** Only timestamp links to the currently playing video are routed into the player. */
internal fun summaryLinkTimestamp(link: String, currentVideoId: String): Int? = runCatching {
    if(!validVideoId(currentVideoId) || !safeSummaryLink(link))return@runCatching null
    val uri=URI(link)
    if(uri.port!=-1 && uri.port!=443)return@runCatching null
    val query=uri.rawQuery.orEmpty().split('&').mapNotNull {part->
        val split=part.split('=',limit=2)
        if(split.size!=2)null else URLDecoder.decode(split[0],"UTF-8") to URLDecoder.decode(split[1],"UTF-8")
    }
    fun param(name: String)=query.firstOrNull {it.first==name}?.second
    val matchesVideo=when(uri.host.lowercase()) {
        "youtube.com","www.youtube.com","m.youtube.com"->
            (uri.path=="/watch" && param("v")==currentVideoId) || uri.path=="/embed/$currentVideoId"
        "youtu.be","www.youtu.be"->uri.path=="/$currentVideoId"
        else->false
    }
    if(!matchesVideo)return@runCatching null
    urlTimeSeconds(param("t") ?: param("start") ?: return@runCatching null)
}.getOrNull()

/**
 * Where a link inside a summary should seek. Gemini sometimes writes a timestamp link with the
 * wrong video ID, so any timed YouTube video link in a summary means that moment in this video.
 */
internal fun summarySeekTarget(link: String, currentVideoId: String): Int? =
    summaryLinkTimestamp(link,currentVideoId)
        ?: if(validVideoId(currentVideoId) && safeSummaryLink(link)) parseYoutubeLink(link)?.start?.takeIf {it>=0} else null

internal fun timestampLabel(seconds: Int): String = if(seconds>=3600)
    "%d:%02d:%02d".format(java.util.Locale.ROOT,seconds/3600,seconds/60%60,seconds%60)
    else "%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)
