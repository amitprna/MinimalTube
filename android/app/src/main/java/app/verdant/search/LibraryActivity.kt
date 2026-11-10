package app.verdant.search

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class LibraryActivity: ComponentActivity() {
    lateinit var access: YoutubeAccess
    var resumed by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        access = YoutubeAccess(this)
        enableEdgeToEdge()
        applyWindowBackground()
        // The bin keeps videos for 24 hours, so clean up a few times a day; screens also prune on load.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("verdant-bin-expiry",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<BinCleanupWorker>(6,TimeUnit.HOURS).build())
        lifecycleScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LibraryStore.cleanup(this@LibraryActivity) } }
        setContent { MinimalTubeTheme { LibraryScreen(this) } }
    }
    override fun onResume() { super.onResume(); resumed++ }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);resumed++}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(activity: LibraryActivity) {
    val scope=rememberCoroutineScope(); val snackbar=remember { SnackbarHostState() }
    val prefs=remember { activity.getSharedPreferences("verdant",0) }
    var account by remember { mutableStateOf(SignInSession.user) }
    var owner by remember { mutableStateOf(prefs.getString("library-owner","device").orEmpty()) }
    var store by remember { mutableStateOf(LibraryStore(activity,owner)) }
    var data by remember { mutableStateOf(LibraryData()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable {mutableStateOf(activity.intent.getBooleanExtra("open-search",false))}
    val searchVm:SearchViewModel=viewModel()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val focus=LocalFocusManager.current
    val searchScroll=rememberLazyListState()
    fun closeSearch(){searching=false;searchVm.clear();focus.clearFocus()}
    var pendingMove by remember { mutableStateOf<VideoMove?>(null) }
    val libraryRevision by LibraryStore.revision.collectAsStateWithLifecycle()
    val syncStatus by LibraryRefresh.status.collectAsStateWithLifecycle()
    val configRevision by AppSettings.revision.collectAsStateWithLifecycle()
    val hasYouTubeKey=remember(configRevision){AppSettings.key("youtube").isNotBlank()}
    val refreshing=syncStatus.running
    var channelJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var connectedEmail by remember { mutableStateOf(SignInSession.user?.email ?: prefs.getString("connected-google-email",null)) }
    val syncGoogle=connectedEmail!=null
    var busy by remember { mutableStateOf(false) }; var progress by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val filterPrefs=remember {activity.getSharedPreferences("verdant-tab-filters",0)}
    var tabGroups by remember {mutableStateOf((0..3).mapNotNull {i -> filterPrefs.getString(owner+"-"+i,null)?.let {i to it}}.toMap())}
    val group=tabGroups[tab]?.takeIf {it==UNGROUPED || it in data.groups}
    fun chooseGroup(value: String?, targetTab: Int=tab) {
        tabGroups=selectTabGroup(tabGroups,targetTab,value)
        filterPrefs.edit().apply {if(value==null)remove(owner+"-"+targetTab) else putString(owner+"-"+targetTab,value)}.apply()
    }
    var selected by remember { mutableStateOf<Channel?>(null) }; var channelVideos by remember { mutableStateOf(emptyList<Video>()) }; var channelNext by remember { mutableStateOf<String?>(null) }
    var addChannel by remember { mutableStateOf(false) }; var address by remember { mutableStateOf("") }
    var manageGroups by remember { mutableStateOf(false) }
    var tagChannel by remember { mutableStateOf<Channel?>(null) }
    var rulesChannel by remember { mutableStateOf<Channel?>(null) }
    var confirmSubscribe by remember { mutableStateOf<Channel?>(null) }
    var confirmUnsubscribe by remember { mutableStateOf<Channel?>(null) }
    var consent by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var continuation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var summaryDialogVideo by remember { mutableStateOf<Video?>(null) }
    val summaryMap by SummaryManager.state.collectAsStateWithLifecycle()
    val watchMap by WatchHistory.state.collectAsStateWithLifecycle()
    var refreshRequested by remember { mutableStateOf(false) }
    // Summaries requested from the library report back here when they finish or fail.
    val requestedSummaries=remember { mutableStateMapOf<String,Video>() }
    LaunchedEffect(summaryMap) {
        for((id,video) in requestedSummaries.toMap()) {
            val rec=summaryMap[id] ?: continue
            if(rec.loading)continue
            requestedSummaries.remove(id)
            if(rec.text.isNotBlank()) scope.launch {
                if(snackbar.showSnackbar("Summary ready","Open",duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed)summaryDialogVideo=video
            } else if(rec.error!=null) scope.launch {
                if(snackbar.showSnackbar(rec.error.lineSequence().first(),"Retry",duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) {
                    requestedSummaries[id]=video;SummaryManager.generate(id,force=true)
                }
            }
        }
    }
    val handleSummaryClick: (Video)->Unit = { video ->
        val rec = SummaryManager.get(video.id)
        if(rec?.text?.isNotBlank()==true) {
            summaryDialogVideo = video
        } else if(rec?.loading==true) {
            scope.launch { snackbar.showSnackbar("Generating summary in background…") }
        } else {
            if(AppSettings.key("gemini").isBlank()) {
                activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra("page", "keys"))
            } else {
                requestedSummaries[video.id]=video
                SummaryManager.generate(video.id,force=rec?.error!=null)
                scope.launch { snackbar.showSnackbar("Finding insights in background…") }
            }
        }
    }
    val initialChannelLink=remember { !activity.intent.getStringExtra("channelId").isNullOrBlank() }
    val homeScroll=rememberLazyListState();val subscriptionsScroll=rememberLazyListState()
    val laterScroll=rememberLazyListState();val binScroll=rememberLazyListState();val channelScroll=rememberLazyListState()
    val listScroll=if(searching)searchScroll else if(selected!=null)channelScroll else when(tab){0->homeScroll;1->subscriptionsScroll;2->laterScroll;else->binScroll}
    LaunchedEffect(selected?.id) {if(selected!=null)channelScroll.scrollToItem(0)}
    LaunchedEffect(searchState.submittedQuery){if(searching)searchScroll.scrollToItem(0)}
    val sortedGroups=remember(data.groups,data.groupOrder){orderedGroups(data)}
    val codes=remember(data.groups,data.groupIds){groupCodes(data.groups,data.groupIds)}
    val filteredChannels=remember(data.channels,data.rules,data.groups,group){data.channels.filter {groupMatches(data,group,it.id)}}
    val binEntries=remember(data.bin){data.bin.associateBy {it.video.id}}
    fun save(value: LibraryData) { data=store.update {mergeLibraryEdit(it,data,value)} }
    fun fail(e: Exception) { if(e is CancellationException) throw e; error=e.message ?: "Could not complete the request." }
    suspend fun api(write: Boolean=false): YoutubeLibraryApi {
        if(!write) return YoutubeLibraryApi("")
        val expected=connectedEmail ?: throw SearchFailure("sign_in_required","Connect Google to change YouTube subscriptions.")
        val token=activity.access.token(true)
        account=SignInSession.user;connectedEmail=account?.email ?: expected
        if(!connectedEmail.equals(expected,true))throw SearchFailure("account_changed","A different account was selected. Import that account's subscriptions before changing them.")
        return YoutubeLibraryApi(token)
    }
    fun permitted(action: () -> Unit) {
        if(!hasYouTubeKey){activity.startActivity(Intent(activity,SettingsActivity::class.java).putExtra("page","keys"));return}
        if(!prefs.getBoolean("library-consent-v4",false)) { continuation=action; consent=true } else action()
    }
    LaunchedEffect(activity.resumed) {
        if(activity.intent.getBooleanExtra("open-search",false)){searching=true;activity.intent.removeExtra("open-search")}
        LibraryRefresh.restore(activity)
        try {LibraryRefresh.request(activity)}catch(e:Exception){fail(e)}
    }
    // Channels followed on YouTube since the last import are added quietly, at most once a day.
    // Nothing is removed here, and no Google prompt is shown; Import from Google stays the full sync.
    LaunchedEffect(activity.resumed,connectedEmail) {
        if(connectedEmail==null)return@LaunchedEffect
        val now=System.currentTimeMillis()
        if(now-prefs.getLong("subscription-sync-at",0) in 0 until 86_400_000L || now-prefs.getLong("subscription-sync-attempt",0) in 0 until 3_600_000L)return@LaunchedEffect
        prefs.edit().putLong("subscription-sync-attempt",now).apply()
        try {
            val remote=YoutubeLibraryApi(activity.access.token(interactive=false))
            val followed=remote.subscriptions()
            val known=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){store.load()}.channels.map {it.id}.toSet()
            val missing=followed.filter {it.id !in known}
            if(missing.isNotEmpty()) {
                val details=remote.channels(missing.map {it.id}).associateBy {it.id}
                data=store.update {addSubscriptions(it,missing.map {channel->details[channel.id] ?: channel})}
                snackbar.showSnackbar(if(missing.size==1)"Added ${missing.single().title}, which you follow on YouTube." else "Added ${missing.size} channels you follow on YouTube.")
            }
            prefs.edit().putLong("subscription-sync-at",System.currentTimeMillis()).apply()
        } catch(e:CancellationException){throw e}
        catch(_:Exception){ /* Stay quiet; the next app open tries again after an hour. */ }
    }
    fun openChannel(channel: Channel) {
        if(busy) return
        permitted {
            selected=channel; channelVideos=data.feed.filter {it.channelId==channel.id}; channelNext=null; busy=true; error=null
            channelJob=scope.launch {
                try {
                    val remote=api(); val full=if(channel.uploads.isBlank()) remote.channels(listOf(channel.id)).firstOrNull() ?: throw SearchFailure("channel","Channel unavailable.") else channel
                    selected=full; val page=remote.uploads(full); channelVideos=page.videos; channelNext=page.nextPage

                } catch(e: Exception) { fail(e) } finally { busy=false }
            }
        }
    }
    fun closeChannel() {channelJob?.cancel();selected=null;error=null;busy=false}
    fun play(video: Video, start: Int=-1) {
        val intent=Intent(activity,PlayerActivity::class.java).putExtra("videoId",video.id).putExtra("title",video.title).putExtra("channel",video.channelTitle).putExtra("channelId",video.channelId)
            .putExtra("thumb",video.thumbnailUrl).putExtra("published",video.publishedAt).putExtra("duration",video.duration.orEmpty()).putExtra("views",video.viewCount.orEmpty())
        if(start>=0)intent.putExtra("start",start)
        activity.startActivity(intent)
    }
    fun refreshNow() {
        permitted {
            refreshRequested=true
            scope.launch {
                val started=try {LibraryRefresh.request(activity,true)}catch(e:Exception){fail(e);false}
                refreshRequested=false
                snackbar.showSnackbar(when {
                    started -> "Refreshing your channels. It continues when you are online."
                    LibraryRefresh.status.value.running -> "A refresh is already running."
                    data.channels.isEmpty() -> "Add channels in Subscriptions first."
                    else -> "Refresh could not start. Check Settings → Refresh."
                })
            }
        }
    }
    fun move(video: Video, destination: Int) {
        val result=moveVideo(data,video,destination,System.currentTimeMillis())
        save(result.first);pendingMove=result.second
    }
    LaunchedEffect(pendingMove) { if(pendingMove!=null) {kotlinx.coroutines.delay(10_000);pendingMove=null} }
    LaunchedEffect(activity.resumed,libraryRevision) {
        account=SignInSession.user
        connectedEmail=SignInSession.user?.email ?: prefs.getString("connected-google-email",null)
        val loadingStore=store;val beforeLoad=data
        val loaded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            // Recover organisation from old guest/account partitions once, without switching libraries.
            for(source in listOfNotNull("device",connectedEmail).distinct().filter {it!=owner}) {
                val marker="recovered-organisation-"+owner+"-"+source
                if(!prefs.getBoolean(marker,false)) {
                    val previous=LibraryStore(activity,source).load()
                    if(previous.groups.isNotEmpty() || previous.rules.isNotEmpty())loadingStore.update {recoverOrganisation(it,previous)}
                    prefs.edit().putBoolean(marker,true).apply()
                }
            }
            loadingStore.load()
        }
        if(store===loadingStore && data===beforeLoad)data=loaded
        val id=activity.intent.getStringExtra("channelId")
        if(!id.isNullOrBlank()) { activity.intent.removeExtra("channelId"); openChannel(Channel(id,activity.intent.getStringExtra("channel").orEmpty())) }
    }
    BackHandler(selected!=null) { closeChannel() }
    BackHandler(searching){closeSearch()}
    val filteredVideos=remember(data.feed,data.channels,data.rules,data.groups,data.videoGroups,data.later,data.bin,data.dismissed,tab,group) {
        when(tab) { 0 -> visibleHome(data,group); 1 -> emptyList(); else -> (if(tab==2)data.later else data.bin).sortedByDescending {it.savedAt}.map {it.video}.filter {groupMatches(data,group,it.channelId,it.id)} }
    }
    // Like Home, the row shows only videos that are not saved or binned: saving one moves it to Watch Later.
    val resumable=remember(watchMap,data.feed,data.later,data.rules,data.groups,data.videoGroups,data.bin,data.dismissed,group) {
        continueWatching(watchMap,visibleHome(data,group),System.currentTimeMillis())
    }
    val tabs=listOf("Home","Subscriptions","Watch Later","Bin")
    Scaffold(containerColor=Paper,snackbarHost={val undo=pendingMove;if(undo!=null) Snackbar(modifier=Modifier.padding(12.dp),action={TextButton(onClick={save(undoVideoMove(data,undo));pendingMove=null}){Text("Undo",color=MaterialTheme.colorScheme.inversePrimary)}},dismissAction={IconButton(onClick={pendingMove=null}){Icon(Icons.Outlined.Close,"Dismiss")}}){Text(if(undo.destination==1)"Saved to Watch Later" else if(undo.destination==2)"Moved to bin" else "Returned to Home")} else SnackbarHost(snackbar)},topBar={
        Column(Modifier.statusBarsPadding().padding(start=20.dp,end=8.dp,top=4.dp,bottom=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(selected!=null && !searching) IconButton(onClick={closeChannel()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
                Text("MinimalTube",fontFamily=FontFamily.Serif,fontSize=22.sp,color=Muted,modifier=Modifier.weight(1f))
                IconButton(onClick={if(searching)closeSearch() else searching=true}) { Icon(if(searching)Icons.Outlined.Close else Icons.Outlined.Search,if(searching)"Close search" else "Search YouTube") }
                if(!searching)IconButton(onClick={showAccount=true}) { Icon(Icons.Outlined.AccountCircle,"Account") }
            }
            if(selected==null || searching) Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if(searching)"Search" else tabs[tab],fontFamily=FontFamily.Serif,fontSize=28.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if(!searching)Text(if(tab==1)"${filteredChannels.size} channels" else "${filteredVideos.size} videos",fontSize=12.sp,color=Muted)
                }
                if(refreshing) RefreshProgress(syncStatus) {activity.startActivity(Intent(activity,SettingsActivity::class.java).putExtra("page","refresh"))}
                else if(!searching && tab==0) IconButton(enabled=!refreshRequested,onClick={refreshNow()}) { Icon(Icons.Outlined.Refresh,"Refresh feed") }
                // Groups are organised from Subscriptions; other tabs only filter by them.
                if(!searching && tab==1)IconButton(onClick={manageGroups=true}) { Icon(Icons.AutoMirrored.Outlined.Label,"Manage groups") }
                IconButton(onClick={activity.startActivity(Intent(activity,SettingsActivity::class.java))}) { Icon(Icons.Outlined.Settings,"Settings") }
            }
            if(searching)InlineSearchInput(searchState,searchVm::edit,{focus.clearFocus();permitted {searchVm.submit()}},{try {activity.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.youtube.com")))}catch(_:android.content.ActivityNotFoundException){scope.launch {snackbar.showSnackbar("No browser is available.")}}})
            else if(selected==null) {
                GroupChips(data,group,{chooseGroup(it)},{name,direction->save(moveGroup(data,name,direction))})
            }
        }
    },bottomBar={ if(selected==null || searching) NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
        val icons=listOf(Icons.Outlined.Home,Icons.Outlined.Subscriptions,Icons.Outlined.WatchLater,Icons.Outlined.DeleteOutline)
        tabs.forEachIndexed { index,title -> NavigationBarItem(selected=tab==index,onClick={closeSearch();tab=index; error=null},icon={Icon(icons[index],title)},label={Text(title,fontSize=11.sp,maxLines=1)}) }
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize().imePadding(),state=listScroll,contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(if(tab==1 && selected==null && !searching)8.dp else 16.dp)) {
            if(!hasYouTubeKey)item(key="setup"){Surface(shape=RoundedCornerShape(20.dp),color=Sand){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text("Add a YouTube API key to start using MinimalTube.",Modifier.weight(1f));IconButton(onClick={activity.startActivity(Intent(activity,SettingsActivity::class.java).putExtra("page","keys"))}){Icon(Icons.Outlined.Info,"Add key and view setup guide")}}}}
            if(!searching && !refreshing && syncStatus.problems.isNotEmpty())item {Text("${syncStatus.problems.size} channels need another check. See Settings.",fontSize=12.sp,color=Muted)}
            if(!searching && busy) item { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); if(progress.isNotEmpty()) Text(progress,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp)) } }
            if(!searching)error?.let { message -> item { Surface(color=Sand,shape=RoundedCornerShape(20.dp)) { Text(message,Modifier.padding(18.dp),fontSize=14.sp) } } }
            val channel=selected
            if(searching) {
                inlineSearchResults(searchState,hasYouTubeKey,summaryMap,watchMap,{permitted {searchVm.retry()}},{permitted {searchVm.loadMore()}},{play(it)},{video->closeSearch();openChannel(Channel(video.channelId,video.channelTitle))},{move(it,1)},handleSummaryClick)
            } else if(channel!=null) {
                item { Column {
                    Row(verticalAlignment=Alignment.CenterVertically) { AsyncImage(channel.thumbnail,null,Modifier.size(64.dp).clip(RoundedCornerShape(22.dp))); Spacer(Modifier.width(14.dp)); Text(channel.title,fontFamily=FontFamily.Serif,fontSize=26.sp) }
                    Text(channel.description,color=Muted,fontSize=13.sp,maxLines=5,modifier=Modifier.padding(vertical=12.dp))
                    val subscribed=data.channels.any { it.id==channel.id }
                    Button(enabled=!busy,onClick={if(subscribed) confirmUnsubscribe=channel else confirmSubscribe=channel},shape=RoundedCornerShape(16.dp)) { Text(if(subscribed) "Unsubscribe" else "Subscribe") }
                } }
                items(channelVideos,key={it.id},contentType={"video"}) { video -> LibraryCard(video,{play(video)},{},{move(video,1)},null,handleSummaryClick,summaryMap[video.id],watchMap[video.id]) }
                if(channelNext!=null) item { OutlinedButton(enabled=!busy,onClick={ busy=true; channelJob=scope.launch { try { val page=api().uploads(channel,channelNext); channelVideos=(channelVideos+page.videos).distinctBy { it.id }; channelNext=page.nextPage } catch(e: Exception){fail(e)} finally{busy=false} } }) { Text("Older videos") } }
            } else if(tab==1) {
                item { OutlinedButton(onClick={addChannel=true},enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) { Icon(Icons.Outlined.Add,null); Text("Add a channel") } }
                val channels=filteredChannels
                if(channels.isEmpty()) item { EmptyLibrary("No channels here.","Add a channel or choose another group.") }
                items(channels,key={it.id},contentType={"channel"}) { c -> Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=1.dp,modifier=Modifier.animateItem()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        AsyncImage(c.thumbnail,null,Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).clickable { openChannel(c) })
                        Text(c.title,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.weight(1f).clickable { openChannel(c) }.padding(horizontal=8.dp))
                        FilledTonalButton(enabled=!busy,onClick={confirmUnsubscribe=c},contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),shape=RoundedCornerShape(12.dp)) { Text("Unsubscribe",fontSize=11.sp,maxLines=1) }
                        IconButton(onClick={tagChannel=c},modifier=Modifier.size(48.dp)) { Icon(if(data.rules[c.id]?.groups.orEmpty().intersect(data.groups).isEmpty())Icons.AutoMirrored.Outlined.Label else Icons.AutoMirrored.Filled.Label,"Assign groups to "+c.title,Modifier.size(19.dp),tint=Muted) }
                        IconButton(onClick={rulesChannel=c},modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.Tune,"Duration filter for "+c.title,Modifier.size(19.dp)) }
                    }
                } }
            } else {
                val videos=filteredVideos
                if(tab==0 && resumable.isNotEmpty()) item(key="continue-watching",contentType="continue") { ContinueWatchingRow(resumable,{play(it)},{move(it,1)}) }
                if(tab!=0) item { Text(if(tab==2) "Saved on this device in MinimalTube." else "Videos leave the bin 24 hours after you bin them.",fontSize=12.sp,color=Muted,lineHeight=18.sp) }
                if(videos.isEmpty()) item { EmptyLibrary(if(tab==0) "A feed with intention." else "Nothing here yet.",if(tab==0) "Add channels in Subscriptions. Your feed updates automatically; tap refresh at the top to check now." else "Your saved videos will appear here.") }
                items(videos,key={it.id},contentType={"video"}) { video -> Column(Modifier.animateItem()) {
                    LibraryCard(video,{play(video)},{openChannel(Channel(video.channelId,video.channelTitle))},if(tab==2)null else ({move(video,1)}),if(tab==3)null else ({move(video,2)}),handleSummaryClick,summaryMap[video.id],watchMap[video.id])
                    if(tab!=0) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={move(video,0)}) { Text("Return to Home") }
                            if(tab==3) binEntries[video.id]?.let { saved -> Text(binRemovalLabel(binExpiry(saved.savedAt),System.currentTimeMillis()),fontSize=11.sp,color=Muted) }
                        }
                    }
                } }

            }
        }
        }
    }
    if(consent) AlertDialog(onDismissRequest={consent=false; continuation=null},title={Text("Your privacy")},text={Text("Searches and public channel requests go to YouTube using your API key. The official player may store cookies and show ads. Groups and saved videos stay on this device. Google sign-in is optional, for YouTube subscriptions. Optional summaries send the public video URL to Gemini. By continuing, you agree to these practices and YouTube’s Terms of Service.")},confirmButton={TextButton(onClick={prefs.edit().putBoolean("library-consent-v4",true).apply();consent=false;val action=continuation;continuation=null;action?.invoke()}){Text("Continue")}},dismissButton={TextButton(onClick={consent=false;continuation=null}){Text("Not now")}})
    if(addChannel) AlertDialog(onDismissRequest={addChannel=false},title={Text("Find a channel")},text={OutlinedTextField(address,{address=it},label={Text("@handle or channel URL")},singleLine=true)},confirmButton={TextButton(enabled=address.isNotBlank() && !busy,onClick={permitted { addChannel=false;busy=true;error=null;channelJob=scope.launch { try { val remote=api();val c=remote.resolveChannel(address); selected=c;val p=remote.uploads(c);channelVideos=p.videos;channelNext=p.nextPage; } catch(e:Exception){fail(e)} finally{busy=false} } }}){Text("Open channel")}},dismissButton={TextButton(onClick={addChannel=false}){Text("Cancel")}})
    confirmSubscribe?.let { c -> AlertDialog(onDismissRequest={confirmSubscribe=null},title={Text("Subscribe to ${c.title}?")},text={Text(if(!syncGoogle) "Follow this channel in your local library. No Google sign-in is needed." else "This adds the channel to your YouTube account. Google may request permission to manage subscriptions.")},confirmButton={TextButton(onClick={confirmSubscribe=null;busy=true;scope.launch { var alreadyOnYouTube=false; try { changeSubscription(syncGoogle,{alreadyOnYouTube=!api(true).subscribe(c.id)}) { save(data.copy(channels=(data.channels+c).distinctBy { it.id },feed=(data.feed+channelVideos.filter { it.channelId==c.id && runCatching {java.time.Instant.parse(it.publishedAt).isAfter(java.time.Instant.now().minusSeconds(86400))}.getOrDefault(false) }).distinctBy { it.id })) };busy=false;val filtered=tabGroups[1].let {it!=null && it!=UNGROUPED};scope.launch {snackbar.showSnackbar((if(alreadyOnYouTube)"Already subscribed on YouTube. Added ${c.title} here." else "Subscribed to ${c.title}.")+(if(filtered)" Choose All in Subscriptions to see it." else ""))} } catch(e:Exception){fail(e)} finally{busy=false} }}){Text("Subscribe")}},dismissButton={TextButton(onClick={confirmSubscribe=null}){Text("Cancel")}}) }
    confirmUnsubscribe?.let { c -> AlertDialog(onDismissRequest={confirmUnsubscribe=null},title={Text("Unsubscribe from "+c.title+"?")},text={Text(if(!syncGoogle) "Remove this channel from your local subscriptions? Your saved videos and groups stay available." else "Remove this subscription from your YouTube account? Your saved videos and groups stay available.")},confirmButton={TextButton(onClick={confirmUnsubscribe=null;busy=true;scope.launch {try {changeSubscription(syncGoogle,{api(true).unsubscribe(c.id)}) {save(data.copy(channels=data.channels.filterNot {it.id==c.id},feed=data.feed.filterNot {it.channelId==c.id}))};busy=false;scope.launch {snackbar.showSnackbar("Unsubscribed from "+c.title)}}catch(e:Exception){fail(e)}finally{busy=false}}}){Text("Unsubscribe")}},dismissButton={TextButton(onClick={confirmUnsubscribe=null}){Text("Keep subscription")}}) }
    if(manageGroups) AlertDialog(onDismissRequest={manageGroups=false},title={Text("Your groups")},text={Column(Modifier.verticalScroll(rememberScrollState())) {GroupEditor(data){save(it)}}},confirmButton={TextButton(onClick={manageGroups=false}){Text("Done")}})
    tagChannel?.let { c -> var tags by remember(c.id) { mutableStateOf(data.rules[c.id]?.groups.orEmpty()) }
        AlertDialog(onDismissRequest={tagChannel=null},title={Text("Group · "+c.title)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            if(data.groups.isEmpty()) Text("Create a group to organise this channel.")
            sortedGroups.forEach { g -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(g in tags,{checked -> tags=if(checked)tags+g else tags-g});Text(codes[g]+" · "+g) } }
            TextButton(onClick={tagChannel=null;manageGroups=true}) {Text("Create group")}
        }},confirmButton={TextButton(onClick={val rule=data.rules[c.id] ?: ChannelRule();save(data.copy(rules=data.rules+(c.id to rule.copy(groups=tags))));tagChannel=null}){Text("Save")}},dismissButton={TextButton(onClick={tagChannel=null}){Text("Cancel")}})
    }
    rulesChannel?.let { c -> val existing=data.rules[c.id] ?: ChannelRule(); var minutes by remember(c.id) { mutableStateOf((durationMinimum(existing)/60).toString()) };var showHelp by remember(c.id){mutableStateOf(false)}
        AlertDialog(onDismissRequest={rulesChannel=null},title={Text("Channel settings")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(c.title,fontWeight=FontWeight.Medium)
            TextButton(onClick={rulesChannel=null;tagChannel=c}) { Icon(if(existing.groups.intersect(data.groups).isEmpty())Icons.AutoMirrored.Outlined.Label else Icons.AutoMirrored.Filled.Label,null,tint=Muted);Spacer(Modifier.width(8.dp));Text("Assign groups") }
            TextButton(onClick={rulesChannel=null;manageGroups=true}) { Text("Create / manage groups") }
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Hide Shorts (approx.)",Modifier.weight(1f),fontSize=14.sp)
                IconButton(onClick={showHelp=!showHelp}) {Icon(Icons.Outlined.Info,"About Hide Shorts",Modifier.size(18.dp))}
                Switch(minutes.toLongOrNull()==3L,{minutes=if(it)"3" else "0"})
            }
            if(showHelp) Text("Hides videos up to 3 minutes, including some regular videos. YouTube does not expose a Shorts flag.",fontSize=12.sp,color=Muted)
            OutlinedTextField(minutes,{minutes=it.filter(Char::isDigit).take(4)},label={Text("Custom minimum · minutes")},supportingText={Text("Show longer videos · 0 for all")},singleLine=true)
        }},confirmButton={TextButton(onClick={val n=(minutes.toLongOrNull() ?: 0)*60;save(data.copy(rules=data.rules+(c.id to existing.copy(hideShorts=n==180L,minimumSeconds=n))));rulesChannel=null}){Text("Save")}},dismissButton={TextButton(onClick={rulesChannel=null}){Text("Cancel")}})
    }
    if(showAccount) AlertDialog(onDismissRequest={showAccount=false},title={Text(if(!syncGoogle)"Google account · optional" else "Google account")},text={Column {
        Text(connectedEmail ?: "Search, playback and subscriptions work without sign-in.")
        Text("Import matches your YouTube subscriptions, removing channels you no longer follow. Groups, channel assignments and saved videos stay on this device, including after sign-out. Premium playback is not enabled here.",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        if(syncGoogle)TextButton(enabled=!busy,onClick={scope.launch {try {GoogleAccount(activity).signOut()}catch(e:Exception){if(e is CancellationException)throw e};account=null;connectedEmail=null;prefs.edit().putBoolean("google-restore",false).putBoolean("sync-google-changes",false).apply();showAccount=false}}){Text("Sign out")}
    }},confirmButton={TextButton(enabled=!busy,onClick={showAccount=false;busy=true;scope.launch {try {
        val token=activity.access.token();account=SignInSession.user;connectedEmail=account?.email ?: prefs.getString("connected-google-email",null)
        val remote=YoutubeLibraryApi(token);val subscriptions=remote.subscriptions()
        val metadata=remote.channels(subscriptions.map {it.id}).associateBy {it.id}
        val imported=subscriptions.map {metadata[it.id] ?: it}
        save(reconcileSubscriptions(data,imported));busy=false
        prefs.edit().putLong("subscription-sync-at",System.currentTimeMillis()).apply()
        snackbar.showSnackbar("Synced "+imported.size+" subscriptions. Groups and saved videos kept.")
    }catch(e:Exception){fail(e);busy=false}}}){Text("Import from Google")}},dismissButton={TextButton(onClick={showAccount=false}){Text("Not now")}})
    summaryDialogVideo?.let { sVideo ->
        val summaryText = summaryMap[sVideo.id]?.text.orEmpty()
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val lineSpacing = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
        val openAt: (Int) -> Unit = { seconds -> summaryDialogVideo = null; play(sVideo, seconds) }
        val renderer = remember(sVideo.id, dark) { summaryMarkdown(activity, dark, sVideo.id, openAt) }
        AlertDialog(
            onDismissRequest = { summaryDialogVideo = null },
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = Sage)
                        Spacer(Modifier.width(8.dp))
                        Text("AI insights", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Sage)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(android.text.Html.fromHtml(sVideo.title, 0).toString(), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("By ${summaryMap[sVideo.id]?.model.orEmpty().ifBlank { "Gemini" }} · tap a timestamp to watch from that moment.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                textSize = 14f
                                setLineSpacing(lineSpacing, 1.05f)
                                setTextIsSelectable(true)
                                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                            }
                        },
                        update = { tv ->
                            tv.setTextColor(textColor)
                            if (tv.tag != summaryText) { setSummaryMarkdown(renderer, tv, summaryText, dark, openAt); tv.tag = summaryText }
                        }
                    )
                }
            },
            confirmButton = { Button(onClick = { summaryDialogVideo = null; play(sVideo) }) { Text("Watch video") } },
            dismissButton = { TextButton(onClick = { summaryDialogVideo = null }) { Text("Close") } }
        )
    }

}

@Composable private fun ContinueWatchingRow(resumable: List<Pair<Video,WatchEntry>>, play: (Video)->Unit, later: (Video)->Unit) {
    Column {
        Text("Continue watching",fontSize=13.sp,color=Muted)
        LazyRow(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            items(resumable,key={it.first.id}) {(video,entry)->
                val title=remember(video.title){android.text.Html.fromHtml(video.title,0).toString()}
                Column(Modifier.width(168.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel="Continue video") {play(video)}) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(14.dp)).background(Sand)) {
                        AsyncImage(video.thumbnailUrl,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(40.dp).background(Color.Black.copy(alpha=.6f),CircleShape).clickable(onClickLabel="Save to Watch Later") {later(video)},contentAlignment=Alignment.Center) {
                            Icon(Icons.Outlined.WatchLater,"Save to Watch Later",Modifier.size(20.dp),tint=Color.White)
                        }
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha=.4f))) {
                            Box(Modifier.fillMaxWidth(entry.fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        }
                    }
                    Text(title,fontSize=13.sp,lineHeight=17.sp,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
                    if(entry.duration>entry.position)Text(timestampLabel(entry.duration-entry.position)+" left",fontSize=11.sp,color=Muted)
                }
            }
        }
    }
}
@Composable private fun EmptyLibrary(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=28.dp)) { Text(title,fontFamily=FontFamily.Serif,fontSize=30.sp);Spacer(Modifier.height(12.dp));Text(subtitle,color=Muted,lineHeight=23.sp) }
}
@Composable internal fun LibraryCard(video: Video, play: ()->Unit, channel: ()->Unit, later: (()->Unit)?, bin: (()->Unit)?, onSummary: ((Video)->Unit)? = null, summary: SummaryRecord? = null, watch: WatchEntry? = null) {
    val title=remember(video.title){android.text.Html.fromHtml(video.title,0).toString()}
    val duration=remember(video.duration){durationLabel(video.duration)}
    val meta=remember(video.publishedAt,video.viewCount){listOfNotNull(relativeTimeLabel(video.publishedAt),viewCountLabel(video.viewCount)).joinToString(" · ")}
    val colors=MaterialTheme.colorScheme
    val progressTint=if(colors.background.luminance()<0.5f)colors.primary else colors.inversePrimary
    Box(Modifier.fillMaxWidth().videoCardSurface()) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Sand).clickable(onClickLabel=if(watch!=null && watch.fraction>0f && !watch.finished)"Continue video" else "Play video",onClick=play)) {
                // YouTube's 4:3 thumbnails carry letterbox bars; cropping to 16:9 removes them.
                AsyncImage(video.thumbnailUrl,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                if(watch?.finished==true) Text("Watched",color=Color.White,fontSize=11.sp,modifier=Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha=.7f),RoundedCornerShape(6.dp)).padding(horizontal=6.dp,vertical=3.dp))
                duration?.let { Text(it,color=Color.White,fontSize=11.sp,modifier=Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color.Black.copy(alpha=.75f),RoundedCornerShape(6.dp)).padding(5.dp)) }
                if(watch!=null && watch.fraction>0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha=.4f))) {
                    Box(Modifier.fillMaxWidth(if(watch.finished)1f else watch.fraction).fillMaxHeight().background(progressTint))
                }
            }
            Column(Modifier.padding(start=16.dp,end=8.dp,top=14.dp,bottom=6.dp)) {
                Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium,lineHeight=22.sp,modifier=Modifier.padding(end=8.dp).clickable(onClick=play))
                TextButton(onClick=channel,enabled=video.channelId.isNotBlank(),contentPadding=PaddingValues(0.dp)) { Text(video.channelTitle,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(meta,fontSize=12.sp,color=Muted,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    val hasSummary=summary?.text?.isNotBlank()==true
                    val isGenerating=summary?.loading==true
                    val failed=!hasSummary && !isGenerating && summary?.error!=null
                    Box(contentAlignment=Alignment.Center) {
                        IconButton(onClick={onSummary?.invoke(video)}) {
                            if(isGenerating) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp,color=Sage)
                            else Icon(Icons.Outlined.AutoAwesome,contentDescription=if(hasSummary)"Read AI summary" else if(failed)"Summary failed. Tap to try again" else "Get AI summary",tint=if(hasSummary)Sage else if(failed)MaterialTheme.colorScheme.error else Muted)
                        }
                        if(hasSummary && !isGenerating) Box(Modifier.align(Alignment.TopEnd).padding(top=8.dp,end=8.dp).size(7.dp).background(Sage,CircleShape))
                    }
                    if(later!=null)IconButton(onClick=later){Icon(Icons.Outlined.WatchLater,"Save to Watch Later",tint=Muted)}
                    if(bin!=null)IconButton(onClick=bin){Icon(Icons.Outlined.DeleteOutline,"Move to bin",tint=Muted)}
                }
            }
        }
    }
}
