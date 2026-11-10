package app.verdant.search

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.net.URI
import java.net.URLDecoder

data class YoutubeLink(val videoId: String, val start: Int? = null)

private val youtubeHosts = setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtube-nocookie.com","www.youtube-nocookie.com")
private val shortHosts = setOf("youtu.be","www.youtu.be")
private val pathVideoRoutes = setOf("shorts","live","embed","v","e")

/** Finds the first YouTube video link in a URL or in shared text such as "Watch this https://youtu.be/…". */
fun parseYoutubeLink(text: String?): YoutubeLink? {
    if(text.isNullOrBlank())return null
    val withScheme=Regex("https?://[^\\s<>\"']+").findAll(text).map {it.value}
    val bare=Regex("(?<![\\w./@:-])(?:(?:www|m|music)\\.)?(?:youtube\\.com|youtu\\.be)/[^\\s<>\"']+").findAll(text).map {"https://"+it.value}
    return (withScheme+bare).map {it.trimEnd('.',',',')',']','!','?',';',':')}.firstNotNullOfOrNull(::parseYoutubeUrl)
}

private fun parseYoutubeUrl(url: String): YoutubeLink? = runCatching {
    val uri=URI(url)
    if(uri.scheme?.lowercase() !in setOf("https","http") || uri.rawUserInfo!=null || uri.port !in setOf(-1,80,443))return@runCatching null
    val host=uri.host?.lowercase() ?: return@runCatching null
    val segments=uri.rawPath.orEmpty().split('/').filter {it.isNotEmpty()}
    fun params(raw: String?)=raw.orEmpty().split('&').mapNotNull {part->
        val pair=part.split('=',limit=2)
        if(pair.size!=2)null else runCatching {URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8")}.getOrNull()
    }
    val query=params(uri.rawQuery)+params(uri.rawFragment)
    fun param(name: String)=query.firstOrNull {it.first==name}?.second
    val id=when(host) {
        in shortHosts->segments.firstOrNull()
        in youtubeHosts->when {
            segments.firstOrNull()=="watch"->param("v")
            segments.size>=2 && segments[0] in pathVideoRoutes->segments[1]
            else->null
        }
        else->null
    }?.takeIf(::validVideoId) ?: return@runCatching null
    YoutubeLink(id,(param("t") ?: param("start"))?.let(::urlTimeSeconds))
}.getOrNull()

/** Entry point for YouTube links opened or shared from other apps; plays them in MinimalTube. */
class LinkActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text=when(intent?.action) {
            Intent.ACTION_VIEW->intent.dataString
            Intent.ACTION_SEND->intent.getStringExtra(Intent.EXTRA_TEXT)
            else->null
        }
        val link=parseYoutubeLink(text)
        if(link==null) Toast.makeText(this,"MinimalTube can open YouTube video links only.",Toast.LENGTH_LONG).show()
        else startActivity(Intent(this,PlayerActivity::class.java).putExtra("videoId",link.videoId).apply {link.start?.let {putExtra("start",it)}})
        finish()
    }
}
