package app.verdant.search

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

@Composable fun GroupChips(data:LibraryData,selected:String?,choose:(String?)->Unit,reorder:(String,Int)->Unit) {
    val order=remember(data.groups,data.groupOrder){orderedGroups(data)}
    val codes=remember(data.groups,data.groupIds){groupCodes(data.groups,data.groupIds)}
    val currentReorder by rememberUpdatedState(reorder)
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        item(key="all"){FilterChip(selected==null,{choose(null)},label={Text("All")})}
        item(key="ungrouped"){FilterChip(selected==UNGROUPED,{choose(if(selected==UNGROUPED)null else UNGROUPED)},label={Text("Ungrouped")})}
        items(order,key={"group:$it"}) {group->
            var dragging by remember {mutableStateOf(false)};var offset by remember {mutableFloatStateOf(0f)};var width by remember {mutableFloatStateOf(100f)}
            Box(Modifier.animateItem().zIndex(if(dragging)1f else 0f).onSizeChanged {width=it.width.toFloat().coerceAtLeast(1f)}
                .graphicsLayer {translationX=offset;scaleX=if(dragging)1.06f else 1f;scaleY=if(dragging)1.06f else 1f}
                .semantics {customActions=listOf(CustomAccessibilityAction("Move earlier"){currentReorder(group,-1);true},CustomAccessibilityAction("Move later"){currentReorder(group,1);true})}
                .pointerInput(group){detectDragGesturesAfterLongPress(onDragStart={dragging=true},onDragEnd={dragging=false;offset=0f},onDragCancel={dragging=false;offset=0f}){change,delta->
                    change.consume();offset+=delta.x
                    if(abs(offset)>width){val direction=if(offset>0)1 else -1;currentReorder(group,direction);offset-=direction*width}
                }}) {
                FilterChip(selected==group,{choose(if(selected==group)null else group)},label={Text(codes[group].orEmpty())})
            }
        }
    }
}

