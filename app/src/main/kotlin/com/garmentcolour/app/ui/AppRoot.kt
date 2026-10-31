package com.garmentcolour.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.garmentcolour.app.AppGraph
import com.garmentcolour.app.ui.capture.CaptureScreen
import com.garmentcolour.app.ui.history.HistoryScreen
import com.garmentcolour.app.ui.result.ResultScreen
import com.garmentcolour.app.ui.settings.SettingsScreen

/** docs/03-frontend/navigation.md */
@Composable
fun AppRoot(graph: AppGraph) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "capture") {
        composable("capture") {
            CaptureScreen(
                graph = graph,
                onResult = { id -> nav.navigate("result/$id") },
                onHistory = { nav.navigate("history") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("result/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ResultScreen(graph = graph, id = id, onBack = { nav.popBackStack() })
        }
        composable("history") {
            HistoryScreen(graph = graph, onOpen = { id -> nav.navigate("result/$id") }, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(graph = graph, onBack = { nav.popBackStack() })
        }
    }
}
