package app.verdant.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Rounded floating surfaces, with a quiet edge and a soft neutral shadow. */
@Composable internal fun Modifier.videoCardSurface(pressed:Boolean=false):Modifier {
    val colors=MaterialTheme.colorScheme
    val dark=colors.background.luminance()<0.5f
    val elevation by animateDpAsState(if(pressed)2.dp else 6.dp,tween(160),label="video-card-elevation")
    val shape=RoundedCornerShape(24.dp)
    return shadow(elevation,shape,clip=false,
        ambientColor=Color.Black.copy(alpha=if(dark)0.35f else 0.14f),
        spotColor=Color.Black.copy(alpha=if(dark)0.45f else 0.20f))
        .background(colors.surface,shape)
        .border(1.dp,if(dark)colors.outlineVariant.copy(alpha=0.7f) else Color.White.copy(alpha=0.9f),shape)
        .clip(shape)
}
