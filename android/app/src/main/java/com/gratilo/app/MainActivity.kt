package com.gratilo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gratilo.app.data.*
import com.gratilo.app.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity: ComponentActivity() {
    private val incoming = MutableStateFlow<String?>(null)
    private val model by viewModels<GratiloViewModel> {
        object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T: ViewModel> create(modelClass: Class<T>): T {
                if (!BuildConfig.DEBUG) require(listOf(BuildConfig.USER_URL, BuildConfig.PAYMENT_URL, BuildConfig.WEB_URL).all { it.startsWith("https://") })
                return GratiloViewModel(GratiloRepository(SecureSessionStore(applicationContext), BuildConfig.USER_URL, BuildConfig.PAYMENT_URL)) as T
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming.value = intent.dataString
        setContent { GratiloTheme { GratiloApp(model, incoming) { incoming.value = null } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent.dataString }
}
