package app.verdant.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Privacy information remains accessible after search moves into the library. */
@Composable internal fun PrivacyInfo() {
    val context=LocalContext.current
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Searches and channel requests go to Google using your YouTube API key. The official YouTube player may store cookies and show ads.",fontSize=14.sp)
        Text("Subscriptions, groups, saved videos and watch positions stay on your device. API keys are encrypted and excluded from backups. No analytics or saved search history are collected.",fontSize=14.sp)
        Text("Optional Google sign-in imports and manages subscriptions. It does not sign the player into YouTube or verify Premium. MinimalTube never asks for your password.",fontSize=14.sp)
        Text("Get summary sends the public video URL and an insights prompt to Gemini using your optional Gemini key. AI insights can be inaccurate.",fontSize=14.sp)
        for((label,url) in listOf("Google Privacy Policy" to "https://policies.google.com/privacy",
            "YouTube Terms of Service" to "https://www.youtube.com/t/terms",
            "Manage Google connections" to "https://myaccount.google.com/connections"))
            TextButton(onClick={try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
                catch(_:ActivityNotFoundException){Toast.makeText(context,"No browser is available.",Toast.LENGTH_SHORT).show()}}){Text(label)}
    }
}
