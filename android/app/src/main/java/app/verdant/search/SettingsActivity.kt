package app.verdant.search

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();applyWindowBackground()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {MinimalTubeTheme {SettingsScreen(this,intent.getStringExtra("page").orEmpty())}}
    }
}
@Composable private fun SettingsScreen(activity:SettingsActivity,initial:String) {
    var page by remember {mutableStateOf(initial)}
    val prefs=remember {activity.getSharedPreferences("verdant",0)}
    val store=remember {LibraryStore(activity,prefs.getString("library-owner","device").orEmpty())}
    val revision by LibraryStore.revision.collectAsStateWithLifecycle()
    var data by remember {mutableStateOf(LibraryData())}
    LaunchedEffect(revision){data=withContext(Dispatchers.IO){store.load()}}
    val scope=rememberCoroutineScope()
    var error by remember {mutableStateOf<String?>(null)}
    val sync by LibraryRefresh.status.collectAsStateWithLifecycle()
    val configRevision by AppSettings.revision.collectAsStateWithLifecycle()
    BackHandler(page.isNotBlank()){page=""}
    Scaffold(containerColor=Paper,topBar={Row(Modifier.statusBarsPadding().padding(16.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick={if(page.isBlank())activity.finish() else page=""}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")}
        Text(when(page){"keys"->"API keys";"refresh"->"Refresh";"groups"->"Groups";"playback"->"Playback";"backup"->"Backup & restore";"links"->"YouTube links";"theme"->"Appearance";"privacy"->"Privacy & about";else->"Settings"},fontFamily=FontFamily.Serif,fontSize=30.sp)
    }}) {padding->Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        when(page) {
            ""->{
                Text("MINIMALTUBE",fontSize=11.sp,color=Muted)
                SettingsBlock {
                    SettingsRow("Refresh","Hourly or once daily",{page="refresh"})
                    HorizontalDivider(color=Line)
                    SettingsRow("Groups","Create, arrange and organise",{page="groups"})
                    HorizontalDivider(color=Line)
                    SettingsRow("Playback","Default speed · "+playbackRateLabel(AppSettings.playbackRate())+" · resume position",{page="playback"})
                    HorizontalDivider(color=Line)
                    SettingsRow("Backup & restore","Save your library to a file or restore it",{page="backup"})
                }
                SettingsBlock {
                    SettingsRow("API keys","Your YouTube and Gemini settings",{page="keys"})
                    HorizontalDivider(color=Line)
                    SettingsRow("YouTube links","Open YouTube links in MinimalTube",{page="links"})
                    HorizontalDivider(color=Line)
                    SettingsRow("Appearance","Light, dark or system",{page="theme"})
                    HorizontalDivider(color=Line)
                    SettingsRow("Privacy & about","Data and account permissions",{page="privacy"})
                }
                Text("MinimalTube ${BuildConfig.VERSION_NAME} · Watch what you choose.",fontSize=12.sp,color=Muted)
            }
            "keys"->KeySettings()
            "backup"->BackupSettings(store)
            "links"->LinkSettings()
            "privacy"->PrivacyInfo()

            "playback"->{
                val selected=remember(configRevision){AppSettings.playbackRate()}
                Text("Default playback speed",color=Muted,fontSize=13.sp)
                SettingsBlock {defaultPlaybackRates.forEach {rate->
                    Row(Modifier.fillMaxWidth().clickable {AppSettings.setText("playback-speed",rate.toString())}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                        RadioButton(selected==rate,{AppSettings.setText("playback-speed",rate.toString())})
                        Text(playbackRateLabel(rate)+(if(rate==1.0)" · Normal" else ""))
                    }
                }}
                Text("Applies to new videos and stays selected after pause or replay. Available speeds depend on YouTube; you can change speed below each video.",color=Muted,fontSize=13.sp)
                val watched by WatchHistory.state.collectAsStateWithLifecycle()
                var confirmClear by remember {mutableStateOf(false)}
                Text("Resume where you left off",color=Muted,fontSize=13.sp)
                SettingsBlock {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Once you have watched two minutes of a video, it continues from where you paused or left it. Shorter visits are not remembered. Positions stay on this device and appear as a progress bar on video cards.",fontSize=13.sp,color=Muted)
                    Text("${watched.size} saved positions",fontSize=13.sp)
                    OutlinedButton(enabled=watched.isNotEmpty(),onClick={confirmClear=true}){Text("Clear watch history")}
                }}
                if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text("Clear watch history?")},text={Text("All saved playback positions and watched markers are removed from this device. Your subscriptions and saved lists are not affected.")},
                    confirmButton={TextButton(onClick={WatchHistory.clear(activity);confirmClear=false}){Text("Clear")}},dismissButton={TextButton(onClick={confirmClear=false}){Text("Cancel")}})
            }
            "theme"->{
                val theme=remember(configRevision){AppSettings.text("theme","light")}
                SettingsBlock {listOf("light" to "Light","dark" to "Dark","system" to "Follow system").forEach {(id,label)->Row(Modifier.fillMaxWidth().clickable {AppSettings.setText("theme",id)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(theme==id,{AppSettings.setText("theme",id)});Text(label)}}}
            }
            "groups"->SettingsBlock {GroupEditor(data){updated->data=store.update {mergeLibraryEdit(it,data,updated)}}}
            "refresh"->{
                var enabled by remember {mutableStateOf(prefs.getBoolean("daily-refresh",false))}
                var hour by remember {mutableIntStateOf(prefs.getInt("refresh-hour",8))}
                var minute by remember {mutableIntStateOf(prefs.getInt("refresh-minute",0))}
                fun schedule(){prefs.edit().putBoolean("daily-refresh",enabled).putInt("refresh-hour",hour).putInt("refresh-minute",minute).apply();LibraryRefresh.schedule(activity);scope.launch {try {LibraryRefresh.request(activity)}catch(e:Exception){error=e.message}}}
                SettingsBlock {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text("Once daily",Modifier.weight(1f));Switch(enabled,{enabled=it;schedule()})}
                    if(enabled)SettingsRow("At %02d:%02d".format(hour,minute),"Once each day",{android.app.TimePickerDialog(activity,{_,h,m->hour=h;minute=m;schedule()},hour,minute,true).show()})
                    HorizontalDivider(color=Line)
                    Button(enabled=!sync.running && data.channels.isNotEmpty(),onClick={scope.launch {try {if(AppSettings.key("youtube").isBlank()){page="keys";return@launch};prefs.edit().putBoolean("library-consent-v4",true).apply();LibraryRefresh.request(activity,true)}catch(e:Exception){error=e.message}}},modifier=Modifier.padding(16.dp)){Text("Refresh now")}
                    if(sync.running)TextButton(onClick={LibraryRefresh.cancel(activity)}){Text("Cancel refresh")}
                }
                Text(if(enabled) "Refreshes once a day, after %02d:%02d. Opening the app earlier will not refresh your feed.".format(hour,minute) else "Refreshes once an hour. Reopening the app within that hour will not start another refresh.",fontSize=13.sp,color=Muted)
                Text("Android may delay background refresh. If it is due, opening the app catches up. Refresh now is always available.",fontSize=12.sp,color=Muted)
                if(sync.running)Text("Refreshing "+sync.completed+" of "+sync.total+" channels",fontSize=12.sp,color=Muted)
                if(sync.lastComplete>0)Text("Last completed check · "+java.time.Instant.ofEpochMilli(sync.lastComplete).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm")),fontSize=12.sp,color=Muted)
                sync.problems.forEach {(id,reason)->Text((data.channels.find {it.id==id}?.title ?: id)+" · "+reason,fontSize=12.sp,color=Muted)}
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }}
}
@Composable fun SettingsBlock(content:@Composable ColumnScope.()->Unit) {Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){Column(content=content)}}
@Composable private fun SettingsRow(title:String,subtitle:String,click:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=click).padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title);Spacer(Modifier.height(4.dp));Text(subtitle,color=Muted,fontSize=12.sp)};Icon(Icons.Outlined.ChevronRight,null,tint=Muted)}}

@Composable fun GroupEditor(data:LibraryData,save:(LibraryData)->Unit) {
    var name by remember {mutableStateOf("")};var code by remember {mutableStateOf("")}
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name,{name=it.take(40)},label={Text("Group name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(code,{code=it.uppercase(java.util.Locale.ROOT).filter {c->c in 'A'..'Z'||c in '0'..'9'}.take(5)},label={Text("Short ID · 1–5 characters")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(enabled=name.isNotBlank() && name.trim()!=UNGROUPED && name.trim() !in data.groups && code.isNotBlank() && code !in setOf("ALL","UNG") && code !in groupCodes(data.groups,data.groupIds).values,onClick={save(data.copy(groups=data.groups+name.trim(),groupIds=data.groupIds+(name.trim() to code),groupOrder=orderedGroups(data)+name.trim()));name="";code=""}){Text("Create group")}
        Text("Long-press and drag group chips in your feed to rearrange. Or use the arrows here.",fontSize=12.sp,color=Muted)
        orderedGroups(data).forEachIndexed {index,g->Row(verticalAlignment=Alignment.CenterVertically){Text(groupCodes(data.groups,data.groupIds)[g]+" · "+g,Modifier.weight(1f));IconButton(enabled=index>0,onClick={save(moveGroup(data,g,-1))}){Icon(Icons.Outlined.KeyboardArrowUp,"Move $g earlier")};IconButton(enabled=index< data.groups.size-1,onClick={save(moveGroup(data,g,1))}){Icon(Icons.Outlined.KeyboardArrowDown,"Move $g later")};IconButton(onClick={save(data.copy(groups=data.groups-g,groupOrder=data.groupOrder-g,groupIds=data.groupIds-g,rules=data.rules.mapValues {it.value.copy(groups=it.value.groups-g)},videoGroups=data.videoGroups.mapValues {it.value-g}))}){Icon(Icons.Outlined.Close,"Delete $g")}}}
    }
}

private data class KeyGuide(val id:String,val title:String,val steps:String,val url:String)
private val guides=listOf(
    KeyGuide("youtube","YouTube API key","1. Open Google Cloud Console and create a project.\n2. Enable YouTube Data API v3 in APIs & Services → Library.\n3. Open Credentials → Create credentials → API key.\n4. Restrict the key to YouTube Data API v3; configure supported application restrictions.\n5. Paste the key here. Quota belongs to this project even when you sign in.","https://console.cloud.google.com/apis/library/youtube.googleapis.com"),
    KeyGuide("gemini","Gemini API key","1. Open Google AI Studio.\n2. Select Get API key and create a key in your own project.\n3. Configure quota/billing as needed.\n4. Restrict the key to the Generative Language API.\n5. Paste it here. Only Get summary sends a public video URL to Gemini. Private and unlisted videos are unsupported.","https://aistudio.google.com/apikey"),
)
@Composable private fun KeySettings() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val revision by AppSettings.revision.collectAsStateWithLifecycle()
    var error by remember {mutableStateOf<String?>(null)}
    val keys=remember(revision){try {AppSettings.keys()}catch(_:Exception){error="Could not unlock stored keys. Reset the key vault below, then re-enter your keys.";emptyList()}.filter {key->guides.any {it.id==key.id}}}
    var editing by remember {mutableStateOf<UserKey?>(null)};var info by remember {mutableStateOf<KeyGuide?>(null)}
    val scope=rememberCoroutineScope()
    Text("Add your YouTube key to get started. Requests go to Google using your project quota. Gemini summaries are optional. To import your YouTube subscriptions, tap the account icon in your library and choose Import from Google.",color=Muted,fontSize=13.sp)
    SettingsBlock {guides.forEach {guide->Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).clickable {editing=keys.find {it.id==guide.id} ?: UserKey(guide.id,guide.title,"")}){Text(guide.title);Text(if(keys.any {it.id==guide.id})"Configured · tap to edit" else if(guide.id=="youtube")"Required · tap to add" else "Optional · tap to add",fontSize=12.sp,color=Muted)};IconButton(onClick={info=guide}){Icon(Icons.Outlined.Info,"How to get "+guide.title)}}}}


    Text("Keys are encrypted on this device and excluded from backups.",fontSize=12.sp,color=Muted)
    ModelPicker("Summary model","gemini-model",remember(revision){AppSettings.model()},allowOff=false,
        note="Flash gives the most detailed summaries. Flash-Lite is faster and lighter. Both follow Google's newest release.")
    ModelPicker("Backup model","gemini-fallback-model",remember(revision){AppSettings.fallbackModel()},allowOff=true,
        note="Used once when the summary model is busy or too slow. The summary shows which model wrote it.")
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)
        if(it.startsWith("Could not unlock"))TextButton(onClick={AppSettings.resetKeys();error=null}){Text("Reset inaccessible keys")}
    }
    editing?.let {entry->
        val name=entry.name;var value by remember(entry.id){mutableStateOf("")};var description by remember(entry.id){mutableStateOf(entry.description)};var saving by remember {mutableStateOf(false)}
        AlertDialog(onDismissRequest={if(!saving)editing=null},title={Text(entry.name.ifBlank {"New key"})},text={Column {

            OutlinedTextField(value,{value=it},label={Text(if(entry.value.isNotBlank())"New value (leave blank to keep)" else "API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            OutlinedTextField(description,{description=it},label={Text("Description · optional")})
            if(entry.value.isNotBlank())TextButton(onClick={scope.launch {try {withContext(Dispatchers.IO){AppSettings.saveKey(entry.copy(value=""))};editing=null}catch(_:Exception){error="Could not remove key."}}}){Text("Remove key")}
        }},confirmButton={TextButton(enabled=!saving && name.isNotBlank() && (value.isNotBlank() || entry.value.isNotBlank()),onClick={saving=true;scope.launch {try {withContext(Dispatchers.IO){AppSettings.saveKey(entry.copy(name=name,value=value.ifBlank {entry.value},description=description))};context.getSharedPreferences("verdant",0).edit().putBoolean("library-consent-v4",true).apply();editing=null}catch(_:Exception){error="Could not save securely. Please try again."}finally{saving=false}}}){Text("Save")}},dismissButton={TextButton(onClick={editing=null}){Text("Cancel")}})
    }
    info?.let {guide->AlertDialog(onDismissRequest={info=null},title={Text(guide.title)},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(guide.steps,fontSize=14.sp);if(guide.id=="youtube" || guide.id=="gemini")Text("\nPackage: ${context.packageName}\nSHA-1: ${signingFingerprint(context)}",fontSize=12.sp)}},confirmButton={TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(guide.url)))}){Text("Open Google setup")}},dismissButton={TextButton(onClick={info=null}){Text("Done")}})}
}
@Suppress("DEPRECATION") fun signingFingerprint(context:android.content.Context):String=try {
    val signatures=if(android.os.Build.VERSION.SDK_INT>=28)context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners else context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_SIGNATURES).signatures!!
    java.security.MessageDigest.getInstance("SHA-1").digest(signatures.first().toByteArray()).joinToString(":"){"%02X".format(it)}
}catch(_:Exception){"Read with apksigner verify --print-certs"}

@Composable private fun BackupSettings(store:LibraryStore) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    var message by remember {mutableStateOf<String?>(null)}
    var working by remember {mutableStateOf(false)}
    var pending by remember {mutableStateOf<LibraryBackup?>(null)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if(uri==null)return@rememberLauncherForActivityResult
        working=true
        scope.launch {
            message=try {
                withContext(Dispatchers.IO) {
                    val text=encodeLibraryBackup(store.load(),WatchHistory.all(context),System.currentTimeMillis())
                    checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use {it.write(text.toByteArray(Charsets.UTF_8))}
                }
                "Backup saved."
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){"Could not save the backup. Choose another location and try again."}
            working=false
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri==null)return@rememberLauncherForActivityResult
        working=true
        scope.launch {
            try {
                pending=withContext(Dispatchers.IO) {
                    val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use {it.readNBytesCompat(MAX_BACKUP_BYTES+1)}
                    if(bytes.size>MAX_BACKUP_BYTES)throw SearchFailure("backup","This file is too large to be a MinimalTube backup.")
                    decodeLibraryBackup(String(bytes,Charsets.UTF_8))
                }
                message=null
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:SearchFailure){message=e.message}
            catch(_:Exception){message="Could not read this file."}
            working=false
        }
    }
    Text("Uninstalling MinimalTube or clearing its storage erases your library, and Android backup is turned off for privacy. Keep a backup file somewhere safe.",color=Muted,fontSize=13.sp)
    SettingsBlock {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Includes subscriptions, groups, channel filters, Watch Later, Bin and watch positions. API keys and summaries are not included.",fontSize=13.sp,color=Muted)
        Button(enabled=!working,onClick={exporter.launch("minimaltube-library-"+java.time.LocalDate.now()+".json")}){Text("Save backup")}
        OutlinedButton(enabled=!working,onClick={importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Restore from file")}
        if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
    }}
    message?.let {Text(it,fontSize=13.sp)}
    pending?.let {backup->AlertDialog(onDismissRequest={pending=null},title={Text("Replace your library?")},
        text={Text("This backup contains "+backupSummary(backup)+". Restoring replaces your current subscriptions, groups and saved lists on this device.")},
        confirmButton={TextButton(onClick={
            pending=null;working=true
            scope.launch {
                message=try {
                    withContext(Dispatchers.IO){store.update {backup.library};WatchHistory.importEntries(context,backup.watch)}
                    "Library restored."
                }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){"Could not restore the backup."}
                working=false
            }
        }){Text("Restore")}},dismissButton={TextButton(onClick={pending=null}){Text("Cancel")}})}
}

/** Reads at most [limit] bytes; InputStream.readNBytes needs API 33. */
private fun java.io.InputStream.readNBytesCompat(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(64*1024)
    while(out.size()<limit) {val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;out.write(buffer,0,n)}
    return out.toByteArray()
}

/** Hosts MinimalTube can open. Android 12+ lets the user enable each one under "Open by default". */
private val linkHosts=listOf("youtube.com","www.youtube.com","m.youtube.com","youtu.be")
private fun enabledLinkHosts(context:android.content.Context):Set<String>? {
    if(android.os.Build.VERSION.SDK_INT<31)return null
    val manager=context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java) ?: return null
    val state=try {manager.getDomainVerificationUserState(context.packageName)}catch(_:Exception){null} ?: return null
    return state.hostToStateMap.filterValues {it!=android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE}.keys
}
@Composable private fun LinkSettings() {
    val context=androidx.compose.ui.platform.LocalContext.current
    var checks by remember {mutableIntStateOf(0)}
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {checks++;onPauseOrDispose {}}
    val enabled=remember(checks){enabledLinkHosts(context)}
    Text("MinimalTube can play YouTube video, Shorts and youtu.be links from other apps, keeping any start time in the link. You can also share a link to MinimalTube from any app.",color=Muted,fontSize=13.sp)
    SettingsBlock {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(enabled!=null) {
            val on=linkHosts.filter {it in enabled}
            Text(if(on.size==linkHosts.size)"MinimalTube opens all YouTube links." else if(on.isEmpty())"MinimalTube is not yet set to open YouTube links." else "Opens links for "+on.joinToString(", ")+".",fontSize=14.sp)
            Text("1. Tap Open settings, then Open by default.\n2. Turn on Open supported links and tap Add link.\n3. Select every YouTube address and tap Add.\nIf the YouTube app is installed, turn off Open supported links in its settings so it doesn't take the links first.",fontSize=13.sp,color=Muted)
        } else Text("Android shows MinimalTube when you open a YouTube link. Choose MinimalTube and tap Always to make it the default.",fontSize=13.sp,color=Muted)
        Button(onClick={
            val uri=Uri.parse("package:"+context.packageName)
            val intent=if(android.os.Build.VERSION.SDK_INT>=31)Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,uri) else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,uri)
            try {context.startActivity(intent)}catch(_:android.content.ActivityNotFoundException){context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,uri))}
        }){Text("Open settings")}
    }}
}

/** The two aliases always point at Google's newest release, so nobody has to know version names. */
internal val presetModels=listOf("gemini-flash-latest" to "Gemini Flash Latest","gemini-flash-lite-latest" to "Gemini Flash-Lite Latest")
internal fun modelChoiceLabel(model:String,allowOff:Boolean):String=
    presetModels.firstOrNull {it.first==model}?.second ?: if(model.isBlank() && allowOff)"Off" else "Custom"

@Composable private fun ModelPicker(title:String,setting:String,saved:String,allowOff:Boolean,note:String) {
    var open by remember {mutableStateOf(false)}
    var custom by remember(saved){mutableStateOf(modelChoiceLabel(saved,allowOff)=="Custom")}
    var name by remember(saved){mutableStateOf(if(presetModels.any {it.first==saved})"" else saved)}
    fun choose(model:String){custom=false;open=false;AppSettings.setText(setting,model)}
    SettingsBlock {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title)
        Box {
            OutlinedButton(onClick={open=true},shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                Text(if(custom)"Custom" else modelChoiceLabel(saved,allowOff),Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown,"Choose "+title.lowercase())
            }
            DropdownMenu(open,{open=false}) {
                presetModels.forEach {(id,label)->DropdownMenuItem(text={Text(label)},onClick={choose(id)})}
                if(allowOff)DropdownMenuItem(text={Text("Off")},onClick={choose("")})
                DropdownMenuItem(text={Text("Custom")},onClick={custom=true;open=false})
            }
        }
        if(custom) {
            OutlinedTextField(name,{name=it},singleLine=true,label={Text("Model name, for example gemini-3.8-flash")},modifier=Modifier.fillMaxWidth())
            TextButton(enabled=validGeminiModel(name),onClick={AppSettings.setText(setting,name.trim().removePrefix("models/"))}){Text(if(name.trim().removePrefix("models/")==saved && saved.isNotBlank())"Saved" else "Save")}
        }
        Text(note,fontSize=12.sp,color=Muted)
    }}
}
