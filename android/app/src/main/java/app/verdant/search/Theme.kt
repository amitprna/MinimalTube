package app.verdant.search

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** One look: six colours from which the whole Material colour scheme is derived. */
internal data class ThemeOption(
    val id: String, val name: String, val dark: Boolean,
    val page: Color, val card: Color, val heading: Color, val body: Color, val muted: Color, val accent: Color
)

internal val DarkLook=ThemeOption("graphite","Graphite",true,Color(0xFF26282B),Color(0xFF303336),Color(0xFFE6E8EA),Color(0xFFC6CACF),Color(0xFF9AA0A7),Color(0xFFC9CED6))
internal val LightLook=ThemeOption("soft-grey","Soft grey",false,Color(0xFFF4F4F5),Color(0xFFFFFFFF),Color(0xFF18181B),Color(0xFF27272A),Color(0xFF63636B),Color(0xFF3F3F46))

internal fun contrastRatio(a: Color, b: Color): Float {
    val (light,dim)=listOf(a.luminance(),b.luminance()).sortedDescending()
    return (light+0.05f)/(dim+0.05f)
}
internal fun colorScheme(option: ThemeOption): ColorScheme = with(option) {
    val onAccent=listOf(Color.White,Color(0xFF1B1D21)).maxBy { contrastRatio(it,accent) }
    val container=lerp(card,accent,if(dark)0.24f else 0.14f)
    val variant=lerp(card,heading,if(dark)0.08f else 0.06f)
    val outline=lerp(page,heading,if(dark)0.42f else 0.45f)
    val outlineVariant=lerp(page,heading,if(dark)0.18f else 0.14f)
    val high=lerp(card,heading,if(dark)0.05f else 0.03f)
    val highest=lerp(card,heading,if(dark)0.09f else 0.06f)
    // The snackbar sits on the inverse surface, so its action needs the accent pushed the other way.
    val inversePrimary=lerp(accent,page,if(dark)0.68f else 0.5f)
    if(dark) darkColorScheme(
        primary=accent,onPrimary=onAccent,primaryContainer=container,onPrimaryContainer=heading,
        secondary=accent,onSecondary=onAccent,secondaryContainer=container,onSecondaryContainer=heading,
        tertiary=accent,onTertiary=onAccent,tertiaryContainer=container,onTertiaryContainer=heading,
        background=page,onBackground=heading,surface=card,onSurface=body,surfaceVariant=variant,onSurfaceVariant=muted,
        outline=outline,outlineVariant=outlineVariant,surfaceTint=card,
        inverseSurface=heading,inverseOnSurface=page,inversePrimary=inversePrimary,
        surfaceDim=lerp(page,Color.Black,0.1f),surfaceBright=lerp(card,heading,0.12f),surfaceContainerLowest=page,
        surfaceContainerLow=lerp(page,card,0.5f),surfaceContainer=card,surfaceContainerHigh=high,surfaceContainerHighest=highest,
        error=Color(0xFFE3ABA0),onError=Color(0xFF43231F),errorContainer=Color(0xFF613630),onErrorContainer=Color(0xFFFFDED8))
    else lightColorScheme(
        primary=accent,onPrimary=onAccent,primaryContainer=container,onPrimaryContainer=heading,
        secondary=accent,onSecondary=onAccent,secondaryContainer=container,onSecondaryContainer=heading,
        tertiary=accent,onTertiary=onAccent,tertiaryContainer=container,onTertiaryContainer=heading,
        background=page,onBackground=heading,surface=card,onSurface=body,surfaceVariant=variant,onSurfaceVariant=muted,
        outline=outline,outlineVariant=outlineVariant,surfaceTint=card,
        inverseSurface=lerp(heading,page,0.08f),inverseOnSurface=page,inversePrimary=inversePrimary,
        surfaceDim=lerp(page,heading,0.08f),surfaceBright=Color.White,surfaceContainerLowest=Color.White,
        surfaceContainerLow=lerp(page,card,0.5f),surfaceContainer=card,surfaceContainerHigh=high,surfaceContainerHighest=highest)
}
private val darkScheme by lazy { colorScheme(DarkLook) }
private val lightScheme by lazy { colorScheme(LightLook) }
internal fun minimalTubeColors(dark: Boolean): ColorScheme = if(dark) darkScheme else lightScheme

/** The app's own light, dark or follow-system choice, readable before any screen is drawn. */
internal fun darkThemeSelected(context: Context): Boolean {
    val theme=AppSettings.text("theme","light")
    return theme=="dark" || (theme=="system" && (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES)
}
/** Paints the window in the page colour, so a screen never flashes white (or black) while it opens. */
internal fun ComponentActivity.applyWindowBackground() {
    window.setBackgroundDrawable(ColorDrawable(minimalTubeColors(darkThemeSelected(this)).background.toArgb()))
}

internal val Paper:Color @Composable get()=MaterialTheme.colorScheme.background
internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.onBackground
internal val Sage:Color @Composable get()=MaterialTheme.colorScheme.primary
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val Sand:Color @Composable get()=MaterialTheme.colorScheme.surfaceVariant
internal val Line:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant
@Composable fun MinimalTubeTheme(content:@Composable ()->Unit) {
    val revision by AppSettings.revision.collectAsState()
    val theme=remember(revision){AppSettings.text("theme","light")}
    val dark=theme=="dark" || (theme=="system" && isSystemInDarkTheme())
    val colors=remember(revision,dark){minimalTubeColors(dark)}
    val view=androidx.compose.ui.platform.LocalView.current
    val activity=androidx.activity.compose.LocalActivity.current
    SideEffect {activity?.let {androidx.core.view.WindowInsetsControllerCompat(it.window,view).apply {
        isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
    }}}
    MaterialTheme(colorScheme=colors,content=content)
}
