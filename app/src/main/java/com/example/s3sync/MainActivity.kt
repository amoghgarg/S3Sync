package com.example.s3sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.s3sync.ui.dashboard.DashboardScreen
import com.example.s3sync.ui.dashboard.DashboardViewModel
import com.example.s3sync.ui.logs.LogsScreen
import com.example.s3sync.ui.logs.LogsViewModel
import com.example.s3sync.ui.settings.SettingsScreen
import com.example.s3sync.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // No auto-sync anymore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!hasPermissions()) {
            requestPermissions()
        }

        setContent {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                            label = { Text("Dashboard") },
                            selected = currentRoute == "dashboard",
                            onClick = { navController.navigate("dashboard") }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.List, contentDescription = "Logs") },
                            label = { Text("Logs") },
                            selected = currentRoute == "logs",
                            onClick = { navController.navigate("logs") }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                            selected = currentRoute == "settings",
                            onClick = { navController.navigate("settings") }
                        )
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable("dashboard") {
                        val dashboardViewModel: DashboardViewModel = viewModel()
                        DashboardScreen(dashboardViewModel)
                    }
                    composable("logs") {
                        val logsViewModel: LogsViewModel = viewModel()
                        LogsScreen(logsViewModel)
                    }
                    composable("settings") {
                        val settingsViewModel: SettingsViewModel = viewModel()
                        SettingsScreen(settingsViewModel)
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        requestPermissionLauncher.launch(permissions)
    }

    private fun hasPermissions(): Boolean {
        val permissions = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
