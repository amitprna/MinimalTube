package app.verdant.search

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One OS WebView using YouTube's unmodified iframe player; no stream extraction. */
class PlayerActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var web: WebView
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var expanded = false
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var videoId = ""
    private var foreground = false
    private var preferredRate = 1.0
    private var dark = false
    // Player state shown by the Compose toolbar and details.
    private var status by mutableStateOf("Loading the YouTube player…")
    private var showRetry by mutableStateOf(false)
    private var showOpenYouTube by mutableStateOf(false)
    private var rates by mutableStateOf(listOf(1.0))
    private var currentRate by mutableDoubleStateOf(1.0)
    private var resumedAt by mutableIntStateOf(0)
    private var position by mutableIntStateOf(0)
    private var duration by mutableIntStateOf(0)
    private var videoTitle by mutableStateOf("")
    private var channelName by mutableStateOf("")
    private var channelId by mutableStateOf("")
    private var savedForLater by mutableStateOf(false)
    private var details: Video? = null
    private fun library() = LibraryStore(this,getSharedPreferences("verdant",0).getString("library-owner","device").orEmpty())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        dark=darkThemeSelected(this)
        if(dark)setTheme(R.style.Theme_MinimalTube_Dark)
        super.onCreate(savedInstanceState)
        applyWindowBackground()
        preferredRate=savedInstanceState?.getDouble("preferred-playback-rate")?.takeIf(::validPlaybackRate) ?: AppSettings.playbackRate()
        currentRate=preferredRate
        videoId = intent.getStringExtra("videoId").orEmpty()
        if (!validVideoId(videoId)) { finish(); return }
        videoTitle=android.text.Html.fromHtml(intent.getStringExtra("title").orEmpty(),0).toString()
        channelName=intent.getStringExtra("channel").orEmpty()
        channelId=intent.getStringExtra("channelId").orEmpty()
        // Links opened from other apps carry only the video ID; fill in the title and channel.
        if(videoTitle.isBlank() && AppSettings.key("youtube").isNotBlank()) lifecycleScope.launch {
            runCatching {YoutubeLibraryApi("").videos(listOf(videoId)).firstOrNull()}.getOrNull()?.let {video->
                details=video
                videoTitle=android.text.Html.fromHtml(video.title,0).toString();channelName=video.channelTitle;channelId=video.channelId
            }
        }
        lifecycleScope.launch { savedForLater=withContext(Dispatchers.IO){library().load().later.any {it.video.id==videoId}} }
        // A summary timestamp opens the video at that moment; otherwise continue where the viewer stopped.
        val requested=if(savedInstanceState==null) intent.getIntExtra("start",-1).takeIf {it>=0} else null
        resumedAt=resumePosition(WatchHistory.get(this,videoId),requested)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (expanded) view.setPadding(0,0,0,0) else view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            view.post { resizePlayer() }
            insets
        }
        val palette=minimalTubeColors(dark)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background.toArgb()) }
        root.addView(page, FrameLayout.LayoutParams(-1,-1))
        page.addView(ComposeView(this).apply { setContent { MinimalTubeTheme { PlayerSurface { PlayerToolbar() } } } }, LinearLayout.LayoutParams(-1,-2))
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                // Keep explicit player links usable without loading password forms in our WebView.
                if (request.hasGesture() && request.url.scheme == "https") {
                    val link=parseYoutubeLink(request.url.toString())
                    if(request.url.host in setOf("accounts.google.com","accounts.youtube.com"))showAccountPlaybackHelp()
                    // The YouTube logo links to this video on YouTube; other video links play here.
                    else if(link?.videoId==videoId)openOriginalVideo()
                    else if(link!=null)startActivity(Intent(this@PlayerActivity,PlayerActivity::class.java).putExtra("videoId",link.videoId).apply {link.start?.let {putExtra("start",it)}})
                    else try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) { status = "No browser is available for this link." }
                }
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { status = "Could not load the player. Check your connection and retry."; showRetry=true }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean { handlePlayerEvent(message.message()); return true }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullView != null) { callback.onCustomViewHidden(); return }
                enterFullscreen(showWeb=false)
                fullView = view; fullCallback = callback; web.visibility = View.INVISIBLE
                root.addView(view, FrameLayout.LayoutParams(-1,-1))
                WindowInsetsControllerCompat(window, root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        page.addView(web, LinearLayout.LayoutParams(-1,dp(220)))
        val summaries=ViewModelProvider(this)[SummaryViewModel::class.java]
        summaries.attach(videoId)
        page.addView(ComposeView(this).apply { setContent { MinimalTubeTheme { PlayerSurface { PlayerDetails(summaries) } } } }, LinearLayout.LayoutParams(-1,0,1f))
        WindowInsetsControllerCompat(window,root).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
        setContentView(root)
        root.post { resizePlayer() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (expanded) exitFullscreen() else finish() }
        })
        loadPlayer(resumedAt)
    }

    private fun handlePlayerEvent(event: String) {
        when {
            event.startsWith("VERDANT:rates:") -> try {
                val values=org.json.JSONArray(event.removePrefix("VERDANT:rates:"))
                rates=(0 until values.length()).map { values.getDouble(it) }.filter { it in 0.1..8.0 }.distinct().sorted()
            } catch (_: Exception) {}
            event.startsWith("VERDANT:rate:") -> event.removePrefix("VERDANT:rate:").toDoubleOrNull()?.takeIf(::validPlaybackRate)?.let {currentRate=it}
            event.startsWith("VERDANT:preferred:") -> if(foreground) event.removePrefix("VERDANT:preferred:").toDoubleOrNull()?.takeIf(::validPlaybackRate)?.let {preferredRate=it; AppSettings.setText("playback-speed",it.toString())}
            event.startsWith("VERDANT:time:") -> {
                val parts=event.removePrefix("VERDANT:time:").split(':')
                val position=parts.getOrNull(0)?.toIntOrNull(); val duration=parts.getOrNull(1)?.toIntOrNull()
                val played=parts.getOrNull(2)?.toIntOrNull() ?: 0
                if(position!=null && duration!=null && shouldTrackWatch(WatchHistory.get(this,videoId),played,position,duration))WatchHistory.record(this,videoId,position,duration)
            }
            event.startsWith("VERDANT:pos:") -> {
                val parts=event.removePrefix("VERDANT:pos:").split(':')
                parts.getOrNull(0)?.toIntOrNull()?.let {position=it}
                parts.getOrNull(1)?.toIntOrNull()?.takeIf {it>0}?.let {duration=it}
            }
            event.startsWith("VERDANT:seek:") -> event.removePrefix("VERDANT:seek:").toIntOrNull()?.let {position=it;status="Jumped to "+timestampLabel(it)}
            event.startsWith("VERDANT:error:") -> {
                val issue=playerFailure(event.removePrefix("VERDANT:error:").toIntOrNull() ?: 0)
                status=issue.message; showRetry=issue.retryable; showOpenYouTube=issue.openYouTube
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            event=="VERDANT:ready" -> {
                showRetry=false; showOpenYouTube=false; status=""
                if (!foreground) pausePlayer()
            }
            event=="VERDANT:playing" -> {
                showRetry=false; showOpenYouTube=false
                if (foreground) { status = ""; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } else pausePlayer()
            }
            event=="VERDANT:paused" || event=="VERDANT:ended" -> window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            event=="VERDANT:timeout" -> { showRetry=true; status = "Player loading is taking longer than expected. Check your connection or retry." }
        }
    }

    @Composable private fun PlayerToolbar() {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={finish()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
            Text("MinimalTube",fontFamily=FontFamily.Serif,fontSize=22.sp,modifier=Modifier.weight(1f))
            IconButton(onClick={enterFullscreen()}) { Icon(Icons.Outlined.Fullscreen,"Full screen") }
        }
    }

    @Composable private fun PlayerSurface(content: @Composable () -> Unit) {
        Surface(color=MaterialTheme.colorScheme.background,contentColor=MaterialTheme.colorScheme.onBackground,content=content)
    }

    @Composable private fun PlayerDetails(summaries: SummaryViewModel) {
        val summary by summaries.state.collectAsStateWithLifecycle()
        val title=videoTitle;val channel=channelName
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SeekControls()
            if(title.isNotBlank())Text(title,fontSize=19.sp,fontWeight=FontWeight.Medium,lineHeight=26.sp,color=MaterialTheme.colorScheme.onBackground)
            if(channel.isNotBlank())TextButton(enabled=channelId.isNotBlank(),contentPadding=PaddingValues(0.dp),onClick={
                startActivity(Intent(this@PlayerActivity,LibraryActivity::class.java).putExtra("channelId",channelId).putExtra("channel",channel))
            }) { Text(channel); Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp)) }
            if(status.isNotBlank())Text(status,fontSize=13.sp,color=Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(rates.size>1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick={open=true},label={Text("Speed · "+playbackRateLabel(currentRate))},leadingIcon={Icon(Icons.Outlined.Speed,null,Modifier.size(18.dp))})
                        DropdownMenu(open,{open=false}) {
                            rates.forEach {rate-> DropdownMenuItem(text={Text(playbackRateLabel(rate)+(if(rate==1.0)" · Normal" else ""))},onClick={open=false;selectRate(rate)},
                                trailingIcon=if(rate==currentRate)({Icon(Icons.Outlined.Check,"Selected")}) else null) }
                        }
                    }
                }
                AssistChip(enabled=!savedForLater,onClick={saveForLater()},label={Text(if(savedForLater)"In Watch Later" else "Watch later")},leadingIcon={Icon(Icons.Outlined.WatchLater,null,Modifier.size(18.dp))})
                AssistChip(onClick={shareVideo()},label={Text("Share")},leadingIcon={Icon(Icons.Outlined.Share,null,Modifier.size(18.dp))})
                AssistChip(enabled=!summary.loading,onClick={requestSummary(summary.text.isNotBlank())},
                    label={Text(if(summary.loading)"Summarising…" else if(summary.text.isNotBlank())"Regenerate summary" else if(summary.error!=null)"Try summary again" else "Summary")},
                    leadingIcon={if(summary.loading)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp) else Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(18.dp))})
                if(showRetry)AssistChip(onClick={retryPlayer()},label={Text("Retry player")},leadingIcon={Icon(Icons.Outlined.Refresh,null,Modifier.size(18.dp))})
                // Only offered when YouTube refuses to play the video here (private, removed or embedding disabled).
                if(showOpenYouTube)AssistChip(onClick={openOriginalVideo()},label={Text("Open in YouTube")},leadingIcon={Icon(Icons.AutoMirrored.Outlined.OpenInNew,null,Modifier.size(18.dp))})
            }
            when {
                summary.loading -> Text(summary.progress.ifBlank {"Finding insights…"},fontSize=13.sp,color=Muted)
                summary.error!=null && summary.text.isBlank() -> Text(summary.error.orEmpty(),fontSize=13.sp,color=MaterialTheme.colorScheme.error)
                summary.text.isNotBlank() -> {
                    HorizontalDivider(Modifier.padding(vertical=4.dp),color=Line)
                    SummaryText(summary.text)
                    Text("AI summary by "+summary.model.ifBlank {"Gemini"}+"; it may be inaccurate.",fontSize=12.sp,color=Muted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    /** The app's own skip and scrub controls, for videos where YouTube's player offers none (such as Shorts). */
    @Composable private fun SeekControls() {
        val total=duration
        if(total<=0)return
        var dragging by remember { mutableStateOf<Float?>(null) }
        val shown=(dragging ?: position.toFloat()).coerceIn(0f,total.toFloat())
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={seekBy(-10)}) { Icon(Icons.Outlined.Replay10,"Back 10 seconds") }
                Slider(value=shown,onValueChange={dragging=it},valueRange=0f..total.toFloat(),modifier=Modifier.weight(1f).semantics {contentDescription="Video position"},
                    onValueChangeFinished={dragging?.let {target->position=target.toInt();seekToTimestamp(target.toInt())};dragging=null})
                IconButton(onClick={seekBy(10)}) { Icon(Icons.Outlined.Forward10,"Forward 10 seconds") }
            }
            Text(timestampLabel(shown.toInt())+" / "+timestampLabel(total),fontSize=12.sp,color=Muted)
        }
    }

    @Composable private fun SummaryText(text: String) {
        val textColor=MaterialTheme.colorScheme.onBackground.toArgb()
        val renderer=remember {summaryMarkdown(this,dark,videoId,::seekToTimestamp)}
        AndroidView(factory={ctx->TextView(ctx).apply {
            textSize=15f;setLineSpacing(dp(4).toFloat(),1.05f);setTextIsSelectable(true)
            movementMethod=android.text.method.LinkMovementMethod.getInstance()
        }},update={view->
            view.setTextColor(textColor)
            if(view.tag!=text){setSummaryMarkdown(renderer,view,text,dark,::seekToTimestamp);view.tag=text}
        },modifier=Modifier.fillMaxWidth())
    }

    private fun requestSummary(regenerate: Boolean) {
        if(AppSettings.key("gemini").isBlank())android.app.AlertDialog.Builder(this).setTitle("Add a Gemini key")
            .setMessage("Add your Gemini API key in Settings to summarise public YouTube videos.")
            .setPositiveButton("API keys"){_,_->startActivity(Intent(this,SettingsActivity::class.java).putExtra("page","keys"))}.setNegativeButton("Not now",null).show()
        else ViewModelProvider(this)[SummaryViewModel::class.java].generate(videoId,force=regenerate)
    }
    private fun selectRate(rate: Double) {
        preferredRate=rate;AppSettings.setText("playback-speed",rate.toString())
        web.evaluateJavascript("if(window.playerSpeed) playerSpeed.select($rate);",null)
    }
    private fun retryPlayer() {
        resumedAt=resumePosition(WatchHistory.get(this,videoId))
        loadPlayer(resumedAt)
    }
    /** Saves this video to Watch Later, from wherever it was opened: a card, Continue watching, search or a link. */
    private fun saveForLater() {
        val video=watchLaterVideo(videoId,details,intent.getStringExtra("title").orEmpty(),channelName,channelId,
            intent.getStringExtra("thumb").orEmpty(),intent.getStringExtra("published").orEmpty(),intent.getStringExtra("duration").orEmpty(),intent.getStringExtra("views").orEmpty())
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO){library().update {moveVideo(it,video,1,System.currentTimeMillis()).first}}
                savedForLater=true;status="Saved to Watch Later."
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){status="Could not save to Watch Later. Try again."}
        }
    }
    private fun shareVideo() {
        val title=videoTitle
        val send=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,listOf(title,youtubeWatchUrl(videoId)).filter {it.isNotBlank()}.joinToString("\n"))
        if(title.isNotBlank())send.putExtra(Intent.EXTRA_SUBJECT,title)
        // MinimalTube accepts shared links itself; leave it out of its own share sheet.
        val chooser=Intent.createChooser(send,"Share video").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,arrayOf(android.content.ComponentName(this,LinkActivity::class.java)))
        try {startActivity(chooser)} catch(_:android.content.ActivityNotFoundException){status="No app is available to share this link."}
    }
    private fun resizePlayer() {
        if (!::web.isInitialized) return
        if (expanded) {
            web.layoutParams=if(web.parent===root) FrameLayout.LayoutParams(-1,-1) else LinearLayout.LayoutParams(-1,root.height.coerceAtLeast(dp(200)))
            fullView?.layoutParams=FrameLayout.LayoutParams(-1,-1)
            return
        }
        val ideal = ((root.width-root.paddingLeft-root.paddingRight) * 9f / 16f).toInt().coerceAtLeast(dp(200))
        web.layoutParams = (web.layoutParams as LinearLayout.LayoutParams).apply { height = ideal.coerceAtMost((root.height-root.paddingTop-root.paddingBottom-dp(56)).coerceAtLeast(dp(200))) }
    }
    private fun loadPlayer(start: Int) {
        showRetry=false; showOpenYouTube=false; rates=listOf(1.0); position=start; duration=0
        status = "Loading the YouTube player…"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web.loadDataWithBaseURL("https://${BuildConfig.APPLICATION_ID}/", playerHtml(videoId, BuildConfig.APPLICATION_ID,preferredRate,start), "text/html", "UTF-8", null)
    }
    private fun showAccountPlaybackHelp() {
        android.app.AlertDialog.Builder(this).setTitle("Watch with your YouTube account")
            .setMessage("MinimalTube sign-in does not sign the embedded player into YouTube. For a private video, open it in YouTube using the account the owner invited.")
            .setPositiveButton("Open in YouTube") {_,_->openOriginalVideo()}.setNegativeButton("Stay here",null).show()
    }
    private fun openOriginalVideo() {
        pausePlayer()
        val link=android.net.Uri.parse(youtubeWatchUrl(videoId))
        try {startActivity(Intent(Intent.ACTION_VIEW,link).setPackage("com.google.android.youtube"))}
        catch(_:android.content.ActivityNotFoundException) {
            // Exclude MinimalTube so a default-link setting cannot send the video straight back here.
            try {startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW,link),"Open video").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,arrayOf(android.content.ComponentName(this,LinkActivity::class.java))))}
            catch(_:android.content.ActivityNotFoundException){status="Install YouTube or a browser to open this video."}
        }
    }
    private fun seekToTimestamp(seconds:Int) {
        if(!foreground || seconds<0)return
        web.evaluateJavascript("window.playerSeek ? playerSeek.seek($seconds) : \"unavailable\";") {result->
            if(result=="\"queued\"")status="Timestamp selected. Waiting for the player…"
            else if(result=="\"unavailable\"")status="The player is loading. Tap the timestamp again when ready."
        }
    }
    private fun seekBy(seconds:Int) {
        if(!foreground)return
        web.evaluateJavascript("window.playerSeek ? playerSeek.seekBy($seconds) : \"unavailable\";") {result->
            if(result=="\"unavailable\"")status="The player is still loading."
        }
    }
    private fun pausePlayer() {
        // Save the exact position first; the pause event reports it again once applied.
        if (::web.isInitialized) web.evaluateJavascript("if(window.playerProgress) playerProgress.report(); if(window.player && typeof player.pauseVideo==='function') player.pauseVideo();", null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun enterFullscreen(showWeb: Boolean=true) {
        if (expanded) return
        expanded=true; previousOrientation=requestedOrientation
        if(showWeb) page.removeView(web)
        page.visibility=View.INVISIBLE
        root.setBackgroundColor(Color.BLACK);root.setPadding(0,0,0,0)
        if(showWeb) root.addView(web,FrameLayout.LayoutParams(-1,-1))
        requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(window,root).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(root);root.post { resizePlayer() }
    }
    private fun exitFullscreen() {
        if (!expanded) return
        fullView?.let { root.removeView(it) }
        fullView = null; expanded=false; web.visibility=View.VISIBLE
        if(web.parent===root) { root.removeView(web);page.addView(web,1,LinearLayout.LayoutParams(-1,dp(220))) }
        page.visibility=View.VISIBLE
        val callback = fullCallback; fullCallback = null; callback?.onCustomViewHidden()
        requestedOrientation=previousOrientation
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root);root.post { resizePlayer() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); ViewCompat.requestApplyInsets(root);root.post { resizePlayer() } }
    override fun onSaveInstanceState(outState:Bundle) {outState.putDouble("preferred-playback-rate",preferredRate);super.onSaveInstanceState(outState)}
    override fun onResume() { super.onResume(); foreground = true; if (::web.isInitialized) { web.onResume(); web.evaluateJavascript("if(window.playerSpeed){playerSpeed.select($preferredRate);playerSpeed.resumed()}", null) } }
    override fun onPause() { foreground = false; pausePlayer(); if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onDestroy() {
        if (::web.isInitialized) { exitFullscreen(); (web.parent as? ViewGroup)?.removeView(web); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}

/** The best available card details for a video saved from the player. */
fun watchLaterVideo(id: String, fetched: Video?, title: String, channel: String, channelId: String, thumbnail: String, published: String, duration: String, views: String): Video {
    require(validVideoId(id))
    return Video(id,title.ifBlank {fetched?.title.orEmpty()}.ifBlank {"Saved video"},channel.ifBlank {fetched?.channelTitle.orEmpty()},
        published.ifBlank {fetched?.publishedAt.orEmpty()},thumbnail.ifBlank {fetched?.thumbnailUrl.orEmpty()}.ifBlank {"https://i.ytimg.com/vi/$id/hqdefault.jpg"},
        duration.ifBlank {fetched?.duration.orEmpty()}.ifBlank {null},views.ifBlank {fetched?.viewCount.orEmpty()}.ifBlank {null},fetched?.available,
        channelId.ifBlank {fetched?.channelId.orEmpty()})
}
fun validVideoId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{11}"))
fun youtubeWatchUrl(id: String): String {require(validVideoId(id));return "https://www.youtube.com/watch?v=$id"}
data class PlayerFailure(val message: String,val retryable: Boolean,val openYouTube: Boolean)
fun playerFailure(code: Int)=when(code) {
    100->PlayerFailure("This video may be private or removed. For private videos, open YouTube with the account the owner invited. MinimalTube sign-in does not sign this player into YouTube.",false,true)
    101,150->PlayerFailure("The owner does not allow embedded playback. You can try watching on YouTube.",false,true)
    153->PlayerFailure("YouTube could not verify the player identity. Retry, or open YouTube.",true,true)
    else->PlayerFailure("YouTube could not play this video. Retry or open YouTube.",true,true)
}

fun playerHtml(id: String, appId: String,preferredRate:Double=1.0,start:Int=0): String {
    require(validVideoId(id))
    require(appId.matches(Regex("[a-z][a-z0-9_.]+")))
    require(validPlaybackRate(preferredRate))
    require(start>=0)
    val startVar=if(start>0)",start:$start" else ""
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin"><style>html,body{margin:0;width:100%;height:100%;background:#000}#player{width:100%;height:100%;border:0}</style></head><body>
<div id="player"></div><script>
$PLAYER_SPEED_SCRIPT
$PLAYER_SEEK_SCRIPT
$PLAYER_PROGRESS_SCRIPT
var player, ready=false;
function report(type,value){console.log('VERDANT:'+type+':'+value)}
var playerSeek=MinimalTubeSeek(function(){return player},report);
var playerSpeed=MinimalTubeSpeed(function(){return player},$preferredRate,report);
var playerProgress=MinimalTubeProgress(function(){return player},report);
function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'$id',width:'100%',height:'100%',playerVars:{controls:1,playsinline:1,fs:1,origin:'https://$appId'$startVar},events:{onReady:function(){ready=true;console.log('VERDANT:ready');playerSpeed.ready();playerSeek.ready();playerProgress.ready()},onStateChange:function(e){playerSpeed.stateChanged(e.data);playerProgress.stateChanged(e.data);if(e.data===1)console.log('VERDANT:playing');if(e.data===2)console.log('VERDANT:paused');if(e.data===0)console.log('VERDANT:ended')},onPlaybackRateChange:function(e){playerSpeed.rateChanged(e.data)},onError:function(e){console.log('VERDANT:error:'+e.data)}}});}
document.addEventListener('visibilitychange',function(){if(document.hidden){playerProgress.report();if(player && player.pauseVideo)player.pauseVideo()}else{playerSpeed.resumed();playerSeek.flush()}});
setTimeout(function(){if(!ready)console.log('VERDANT:timeout')},20000);
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
}
