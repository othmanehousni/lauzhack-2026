package ch.lauzhack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import ch.lauzhack.app.link.Phone
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var phone: Phone
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) phone.listen(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lang = Lang.of(java.util.Locale.getDefault().language)
        phone =Phone(this, lifecycleScope)
        phone.manual = intent.getStringExtra("laptop") // adb shell am start -n ch.lauzhack.app/.MainActivity -e laptop 192.168.1.20
        lifecycleScope.launch {
            phone.state.map { it.connected }.distinctUntilChanged().collect { paired ->
                if (paired) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (!hasMic()) mic.launch(Manifest.permission.RECORD_AUDIO)
        setContent { App(phone.state, phone::onPick, phone.micIssue.collectAsState().value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("laptop")?.let { setIntent(intent); phone.manual = it } // kept should the activity be recreated
    }

    override fun onStart() {
        super.onStart()
        if (hasMic()) phone.listen(true)
    }

    override fun onStop() {
        phone.listen(false)
        super.onStop()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
