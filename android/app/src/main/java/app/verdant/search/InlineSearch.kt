package app.verdant.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun InlineSearchInput(state:SearchState,onEdit:(String)->Unit,onSubmit:()->Unit,openYouTube:()->Unit) {
    val focus=remember {FocusRequester()}
    LaunchedEffect(Unit){focus.requestFocus()}
    Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(state.query,onEdit,modifier=Modifier.weight(1f).focusRequester(focus),
            placeholder={Text("Search YouTube")},singleLine=true,shape=RoundedCornerShape(20.dp),
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={onSubmit()}),
            trailingIcon=if(state.query.isNotEmpty())({IconButton(onClick={onEdit("")}){Icon(Icons.Outlined.Close,"Clear search")}}) else null)
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick=onSubmit,enabled=state.query.isNotBlank(),modifier=Modifier.size(48.dp),shape=RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Search,"Find videos")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Image(painterResource(R.drawable.youtube_attribution),"Developed with YouTube",
            Modifier.width(104.dp).height(28.dp).background(Color(0xFF0F0F0F),RoundedCornerShape(4.dp)).clickable(onClick=openYouTube),contentScale=ContentScale.Fit)
        Spacer(Modifier.weight(1f))
        if(state.searched && !state.loading)Text("${state.videos.size} results",color=Muted,fontSize=11.sp)
    }
}

internal fun LazyListScope.inlineSearchResults(
    state:SearchState,hasKey:Boolean,summaries:Map<String,SummaryRecord>,watched:Map<String,WatchEntry>,retry:()->Unit,loadMore:()->Unit,
    play:(Video)->Unit,channel:(Video)->Unit,later:(Video)->Unit,
    onSummary:((Video)->Unit)?=null
) {
    if(state.loading)item(key="search-loading") {
        Row(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
            Spacer(Modifier.width(12.dp));Text("Searching…",color=Muted,fontSize=13.sp)
        }
    }
    items(state.videos,key={"search-"+it.id},contentType={"video"}) {video->
        LibraryCard(video,{play(video)},{channel(video)},{later(video)},null,onSummary,summaries[video.id],watched[video.id])
    }
    if(state.error!=null)item(key="search-error") {
        Surface(shape=RoundedCornerShape(20.dp),color=Sand) {Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(state.error,color=MaterialTheme.colorScheme.onSurface,fontSize=14.sp)
            if(state.errorCode!="quota_exhausted")TextButton(onClick=retry){Text("Try again")}
        }}
    }
    else if(state.searched && !state.loading && state.videos.isEmpty())item(key="search-empty") {
        Column(Modifier.padding(vertical=24.dp)) {
            Text("No videos found.",fontFamily=FontFamily.Serif,fontSize=25.sp)
            Spacer(Modifier.height(8.dp));Text("Try another search.",color=Muted)
        }
    }
    else if(!state.searched && !state.loading && hasKey)item(key="search-hint") {
        Text("Find videos on YouTube. Open a channel from any result.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(vertical=16.dp))
    }
    if(state.nextPage!=null && state.error==null)item(key="search-more") {
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            if(state.appending)CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
            else OutlinedButton(onClick=loadMore,shape=RoundedCornerShape(16.dp)){Text("More results")}
        }
    }
}
