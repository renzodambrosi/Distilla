package io.github.renzodambrosi.distilla

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.renzodambrosi.distilla.core.UrlExtractor
import io.github.renzodambrosi.distilla.ui.DistillaApp
import io.github.renzodambrosi.distilla.ui.MainViewModel
import io.github.renzodambrosi.distilla.ui.theme.DistillaTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the playback notification: open the listening queue. */
        const val EXTRA_OPEN_QUEUE = "io.github.renzodambrosi.distilla.OPEN_QUEUE"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On screen rotation the intent is the same one: don't reload
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            DistillaTheme {
                DistillaApp(vm, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUEUE, false) == true) {
            vm.openQueue()
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        ).joinToString(" ")
        val url = UrlExtractor.find(text)
        if (url != null) {
            vm.open(url, fromShare = true)
        } else {
            Toast.makeText(this, R.string.no_link_shared, Toast.LENGTH_LONG).show()
        }
    }
}
