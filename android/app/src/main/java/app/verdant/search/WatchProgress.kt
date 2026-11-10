package app.verdant.search

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last known playback position for one video, in whole seconds. */
data class WatchEntry(val position: Int, val duration: Int, val updatedAt: Long) {
    val fraction: Float get() = if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f
    val finished: Boolean get() = duration>0 && (position>=duration-FINISH_MARGIN_SECONDS || fraction>=0.95f)
}
const val FINISH_MARGIN_SECONDS = 15
const val MIN_RESUME_SECONDS = 5
/** A video joins the watch history only after this much actual playback, so a quick look leaves no trace. */
const val MIN_TRACK_SECONDS = 120
const val RESUME_REWIND_SECONDS = 2
const val MAX_WATCH_ENTRIES = 1000

/** Videos already in the history keep updating; new ones need two minutes of playback or to be played to the end. */
fun shouldTrackWatch(existing: WatchEntry?, playedSeconds: Int, position: Int, duration: Int): Boolean =
    existing!=null || playedSeconds>=MIN_TRACK_SECONDS || (duration>0 && position>=duration)

/** An explicit timestamp wins; otherwise continue an unfinished video, rewinding slightly for context. */
fun resumePosition(entry: WatchEntry?, requested: Int? = null): Int = when {
    requested!=null && requested>=0 -> requested
    entry==null || entry.finished || entry.position<MIN_RESUME_SECONDS -> 0
    else -> (entry.position-RESUME_REWIND_SECONDS).coerceAtLeast(0)
}
fun encodeWatchEntry(entry: WatchEntry) = "${entry.position},${entry.duration},${entry.updatedAt}"
fun parseWatchEntry(value: String?): WatchEntry? {
    val parts=value?.split(',') ?: return null
    if(parts.size!=3)return null
    val position=parts[0].toIntOrNull() ?: return null
    val duration=parts[1].toIntOrNull() ?: return null
    val updated=parts[2].toLongOrNull() ?: return null
    return if(position<0 || duration<0) null else WatchEntry(position,duration,updated)
}
/** Keep the most recently watched videos so storage stays small. */
fun pruneWatchHistory(entries: Map<String,WatchEntry>, max: Int = MAX_WATCH_ENTRIES): Map<String,WatchEntry> =
    if(entries.size<=max) entries else entries.entries.sortedByDescending {it.value.updatedAt}.take(max).associate {it.key to it.value}

const val CONTINUE_WATCHING_DAYS = 14
const val CONTINUE_WATCHING_MAX = 10
/** Started but unfinished videos from the last two weeks, most recent first. */
fun continueWatching(watch: Map<String,WatchEntry>, videos: List<Video>, now: Long): List<Pair<Video,WatchEntry>> {
    val known=videos.filter {it.title.isNotBlank() && it.title!="Saved video"}.associateBy {it.id}
    val cutoff=now-CONTINUE_WATCHING_DAYS*86_400_000L
    return watch.entries.asSequence()
        .filter {(_,entry)->!entry.finished && entry.position>=MIN_TRACK_SECONDS && entry.updatedAt>=cutoff}
        .sortedByDescending {it.value.updatedAt}
        .mapNotNull {(id,entry)->known[id]?.let {it to entry}}
        .take(CONTINUE_WATCHING_MAX).toList()
}

/** Local-only watch positions; never synced to YouTube. */
object WatchHistory {
    private var prefs: SharedPreferences? = null
    private val loaded = CompletableDeferred<Unit>()
    private val entries = MutableStateFlow<Map<String,WatchEntry>>(emptyMap())
    val state = entries.asStateFlow()

    /** Reads stored positions; call off the main thread. */
    @Synchronized fun initialize(context: Context) {
        if(prefs!=null)return
        val sp=context.applicationContext.getSharedPreferences("greentube-watch",0)
        prefs=sp
        val all=sp.all.mapNotNull {(id,value)->if(validVideoId(id))parseWatchEntry(value as? String)?.let {id to it} else null}.toMap()
        val kept=pruneWatchHistory(all)
        if(kept.size<all.size)sp.edit().apply {(all.keys-kept.keys).forEach(::remove)}.apply()
        entries.value=kept+entries.value
        loaded.complete(Unit)
    }
    fun get(context: Context, videoId: String): WatchEntry? {
        entries.value[videoId]?.let {return it}
        if(loaded.isCompleted)return null
        return parseWatchEntry(context.applicationContext.getSharedPreferences("greentube-watch",0).getString(videoId,null))
    }
    fun record(context: Context, videoId: String, position: Int, duration: Int, now: Long = System.currentTimeMillis()) {
        if(!validVideoId(videoId) || position<0 || duration<0)return
        val entry=WatchEntry(position,duration,now)
        entries.value=entries.value+(videoId to entry)
        val sp=prefs ?: context.applicationContext.getSharedPreferences("greentube-watch",0)
        sp.edit().putString(videoId,encodeWatchEntry(entry)).apply()
        if(loaded.isCompleted && entries.value.size>MAX_WATCH_ENTRIES+50) {
            val kept=pruneWatchHistory(entries.value)
            sp.edit().apply {(entries.value.keys-kept.keys).forEach(::remove)}.apply()
            entries.value=kept
        }
    }
    /** Every stored position; waits for the background load when needed. Call off the main thread. */
    fun all(context: Context): Map<String,WatchEntry> { initialize(context); return entries.value }
    /** Restores positions from a backup, keeping whichever copy is newer. */
    fun importEntries(context: Context, imported: Map<String,WatchEntry>) {
        initialize(context)
        val newer=imported.filter {(id,entry)->validVideoId(id) && (entries.value[id]?.updatedAt ?: -1)<entry.updatedAt}
        if(newer.isEmpty())return
        entries.value=pruneWatchHistory(entries.value+newer)
        val sp=checkNotNull(prefs)
        sp.edit().apply {newer.forEach {(id,entry)->if(id in entries.value)putString(id,encodeWatchEntry(entry))}}.apply()
    }
    fun clear(context: Context) {
        entries.value=emptyMap()
        (prefs ?: context.applicationContext.getSharedPreferences("greentube-watch",0)).edit().clear().apply()
    }
}

/**
 * Reports the position to save ('time': every five seconds while playing, and whenever
 * playback pauses or ends, with the seconds actually played in this session) and the position to display ('pos': every second while playing).
 * Nothing is saved until the viewer has actually started the video, so opening and leaving
 * a video never overwrites a saved position with zero.
 */
internal val PLAYER_PROGRESS_SCRIPT = """
function MinimalTubeProgress(getPlayer, emit) {
  var started = false, timer = null, last = null, lastShown = null, ticks = 0, played = 0;
  function read() {
    var player = getPlayer();
    if (!player || typeof player.getCurrentTime !== 'function') return null;
    try {
      var time = Math.floor(player.getCurrentTime()), duration = Math.floor(player.getDuration() || 0);
      if (!Number.isFinite(time) || time < 0) return null;
      if (!Number.isFinite(duration) || duration < 0) duration = 0;
      return [time, duration];
    } catch (_) { return null; }
  }
  function send(value, force) {
    if (!started || value === null) return false;
    var text = value[0] + ':' + value[1];
    if (!force && text === last) return false;
    last = text; emit('time', text + ':' + played); return true;
  }
  function show(value) {
    if (value === null) return;
    var text = value[0] + ':' + value[1];
    if (text !== lastShown) { lastShown = text; emit('pos', text); }
  }
  function stop() { if (timer !== null) { clearInterval(timer); timer = null; } }
  return {
    ready: function() { show(read()); },
    stateChanged: function(state) {
      if (state === 1) {
        started = true; stop(); ticks = 0;
        timer = setInterval(function() {
          var value = read(); show(value); played++;
          if (++ticks % 5 === 0) send(value, false);
        }, 1000);
      } else if (state === 2) {
        stop(); var paused = read(); show(paused); send(paused, true);
      } else if (state === 0) {
        stop();
        var value = read();
        if (value !== null && value[1] > 0) value = [value[1], value[1]];
        show(value); send(value, true);
      } else show(read());
    },
    report: function() { return send(read(), true); },
    dispose: stop
  };
}
""".trimIndent()
