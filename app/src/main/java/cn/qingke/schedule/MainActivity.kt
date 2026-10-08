package cn.qingke.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qingke.schedule.data.AppViewModel
import cn.qingke.schedule.ui.QingKeRoot
import cn.qingke.schedule.ui.QingKeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = model.preferences.collectAsStateWithLifecycle().value
            QingKeTheme(prefs.theme) { QingKeRoot(model) }
        }
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { model.changed() }
    }
}
