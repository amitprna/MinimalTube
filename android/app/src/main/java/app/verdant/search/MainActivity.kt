package app.verdant.search

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Compatibility route for older internal links; search lives in the library. */
class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this,LibraryActivity::class.java).putExtra("open-search",true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
