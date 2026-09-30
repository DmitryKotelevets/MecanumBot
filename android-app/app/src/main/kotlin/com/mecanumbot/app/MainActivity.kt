package com.mecanumbot.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.mecanumbot.app.ui.Root

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as MecanumApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Root(graph) }
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
