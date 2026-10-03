package com.mecanumbot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.mecanumbot.app.ui.Root

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as MecanumApp).graph
    private var askingPermissions = false

    // Whatever the answer, start the service: without CAMERA it runs without video (spec §3).
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askingPermissions = false
        startRobotService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { Root(graph) }
        }
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && savedInstanceState == null) {
            askingPermissions = true
            permissions.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        graph.onForeground(true)
        // A camera service may only be started while the app is visible; starting again is harmless.
        if (!askingPermissions) startRobotService()
    }

    override fun onPause() {
        graph.onForeground(false) // a call or notification stops local driving; a remote pilot continues
        super.onPause()
    }

    private fun startRobotService() {
        startForegroundService(Intent(this, RobotService::class.java))
    }
}
