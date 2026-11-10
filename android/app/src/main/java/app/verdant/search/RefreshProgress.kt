package app.verdant.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RefreshProgress(status: FeedSyncStatus, openSettings: () -> Unit) {
    val fraction=if(status.total==0) 0f else (status.completed.toFloat()/status.total).coerceIn(0f,1f)
    val animated by animateFloatAsState(fraction,tween(250),label="Refresh progress")
    val percent=(fraction*100).toInt()
    IconButton(onClick=openSettings) {
        Box(Modifier.size(36.dp).clearAndSetSemantics {
            contentDescription="Refresh $percent percent complete. ${status.completed} of ${status.total} channels. Open refresh settings."
        },contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={animated},modifier=Modifier.size(36.dp),strokeWidth=2.dp,
                color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant)
            Text("$percent%",fontSize=10.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurface)
        }
    }
}
