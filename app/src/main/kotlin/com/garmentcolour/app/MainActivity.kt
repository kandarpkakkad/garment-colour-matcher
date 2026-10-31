package com.garmentcolour.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.garmentcolour.app.ui.AppRoot
import com.garmentcolour.app.ui.theme.GarmentColourTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as GarmentColourApp).graph
        setContent {
            GarmentColourTheme {
                AppRoot(graph)
            }
        }
    }
}
