package com.pablo.yumemi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pablo.yumemi.notify.AppVisibility
import com.pablo.yumemi.notify.Notifier
import com.pablo.yumemi.ui.YumemiApp
import com.pablo.yumemi.ui.theme.YumemiTheme

class MainActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
    }

    override fun onStop() {
        AppVisibility.visible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute = intent.getStringExtra(Notifier.EXTRA_ROUTE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light-only app: always dark system-bar icons over the pale gradient.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Android 13+: the "working" notification and the new-chat / report alerts need this permission.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        pendingRoute = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        setContent {
            YumemiTheme { YumemiApp(openRoute = pendingRoute, onRouteOpened = { pendingRoute = null }) }
        }
    }
}
