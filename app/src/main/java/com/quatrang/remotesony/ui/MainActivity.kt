package com.quatrang.remotesony.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.quatrang.remotesony.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            vm.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }
        setContent {
            RemoteTheme { App(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
        if (vm.state.value.showSetup) vm.discovery.start()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
        vm.discovery.stop()
    }
}

val SonyBlue = Color(0xFF2F7BEA)

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = SonyBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1B3A66),
        onPrimaryContainer = Color(0xFFD6E4FF),
        background = Color(0xFF101418),
        surface = Color(0xFF101418),
        surfaceVariant = Color(0xFF1E242B),
        surfaceContainerHigh = Color(0xFF232A32),
        onSurface = Color(0xFFE6E9EE),
        onSurfaceVariant = Color(0xFFA9B1BC),
        error = Color(0xFFFF6B6B),
    ) else lightColorScheme(
        primary = Color(0xFF1F5FBF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE8FF),
        onPrimaryContainer = Color(0xFF0C2E63),
        background = Color(0xFFF5F7FA),
        surface = Color(0xFFF5F7FA),
        surfaceVariant = Color(0xFFE6EAF0),
        surfaceContainerHigh = Color(0xFFFFFFFF),
        onSurface = Color(0xFF15191E),
        onSurfaceVariant = Color(0xFF59616C),
        error = Color(0xFFD32F2F),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
