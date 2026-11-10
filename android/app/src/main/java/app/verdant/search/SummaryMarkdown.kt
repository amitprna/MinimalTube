package app.verdant.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import java.net.URI

internal fun safeSummaryLink(link:String)=runCatching {
    val uri=URI(link)
    uri.scheme.equals("https",ignoreCase=true) && !uri.host.isNullOrBlank() && uri.rawUserInfo==null
}.getOrDefault(false)

/** Native styled text only: no HTML/WebView execution or remote image loader. */
internal fun summaryMarkdown(context:Context,dark:Boolean,videoId:String,onTimestamp:(Int)->Unit):Markwon {
    val palette=minimalTubeColors(dark)
    fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    return Markwon.builder(context).usePlugin(object:AbstractMarkwonPlugin() {
        override fun configureTheme(builder:MarkwonTheme.Builder) {
            builder.linkColor(palette.primary.toArgb()).blockMargin(dp(20)).bulletWidth(dp(4))
                .headingBreakHeight(0).headingTextSizeMultipliers(floatArrayOf(1.4f,1.25f,1.15f,1.05f,1f,1f))
                .codeTextColor(palette.onSurface.toArgb()).codeBackgroundColor(palette.surfaceVariant.toArgb())
        }
        override fun configureConfiguration(builder:MarkwonConfiguration.Builder) {
            builder.linkResolver {view,link->
                val seconds=summarySeekTarget(link,videoId)
                if(seconds!=null)onTimestamp(seconds)
                else if(safeSummaryLink(link))try {view.context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))}
                catch(_:ActivityNotFoundException){Toast.makeText(view.context,"No browser is available for this link.",Toast.LENGTH_SHORT).show()}
            }
        }
    }).build()
}

internal fun setSummaryMarkdown(renderer:Markwon,view:TextView,text:String,dark:Boolean,onTimestamp:(Int)->Unit) {
    val rendered=SpannableStringBuilder(renderer.toMarkdown(text))
    val linkColor=minimalTubeColors(dark).primary.toArgb()
    for(cue in summaryTimestamps(rendered.toString())) {
        // Markdown links already have their own routing; do not stack click targets.
        if(rendered.getSpans(cue.start,cue.end,ClickableSpan::class.java).isNotEmpty())continue
        rendered.setSpan(object:ClickableSpan() {
            override fun onClick(widget:View){onTimestamp(cue.seconds)}
            override fun updateDrawState(ds:TextPaint){ds.color=linkColor;ds.isUnderlineText=true}
        },cue.start,cue.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    renderer.setParsedMarkdown(view,rendered)
}
