package com.mecanumbot.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.mecanumbot.app.ui.Root

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as MecanumApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { Root(graph) }
        }
    }

    override fun onResume() {
        super.onResume()
        graph.onForeground(true)
    }

    override fun onPause() {
        graph.onForeground(false) // a call or notification must stop the robot (DESIGN.md §5.4)
        super.onPause()
    }
}
