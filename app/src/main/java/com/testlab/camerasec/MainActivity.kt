package com.testlab.camerasec

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.testlab.camerasec.camera.CameraRunState
import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import com.testlab.camerasec.ui.screens.CameraPreviewScreen
import com.testlab.camerasec.ui.screens.LogScreen
import com.testlab.camerasec.ui.screens.PermissionScreen
import com.testlab.camerasec.ui.screens.SecurityTestScreen
import com.testlab.camerasec.ui.screens.StreamingScreen
import com.testlab.camerasec.ui.theme.CameraSecLabTheme

/**
 * Single-activity app. This Activity is also where we attach a lifecycle
 * observer used purely for REPORTING — see the class-level note in
 * SecurityTestScreen.kt. When the Activity stops (backgrounded, screen off,
 * or otherwise), we proactively stop the camera and streaming ourselves so
 * behavior is deterministic and honestly reported, rather than relying on
 * CameraX's own unbind-on-stop timing and calling that "hiding" anything.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SecurityTestLog.log(LogCategory.LIFECYCLE, "App entered FOREGROUND")
            }

            override fun onStop(owner: LifecycleOwner) {
                SecurityTestLog.log(LogCategory.LIFECYCLE, "App entered BACKGROUND (or screen turned off)")
                if (viewModel.cameraController.runState.value == CameraRunState.RUNNING) {
                    viewModel.stopPreview("Camera stopped when app entered this state.")
                }
                if (viewModel.serverRunning.value) {
                    SecurityTestLog.log(
                        LogCategory.STREAMING,
                        "Streaming stopped — app left the foreground, so the local server was shut down."
                    )
                    viewModel.stopStreaming()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                SecurityTestLog.log(LogCategory.LIFECYCLE, "App process ending — all camera and network resources released.")
            }
        })

        setContent {
            CameraSecLabTheme {
                AppRoot(viewModel)
            }
        }
    }
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PERMISSION("Permission", Icons.Filled.VerifiedUser),
    PREVIEW("Preview", Icons.Filled.CameraAlt),
    SECURITY("Security Test", Icons.Filled.Security),
    STREAMING("Streaming", Icons.Filled.Videocam),
    LOG("Log", Icons.AutoMirrored.Filled.List)
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = Tab.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (tabs[selectedTab]) {
            Tab.PERMISSION -> PermissionScreen(padding)
            Tab.PREVIEW -> CameraPreviewScreen(padding, viewModel)
            Tab.SECURITY -> SecurityTestScreen(padding, viewModel)
            Tab.STREAMING -> StreamingScreen(padding, viewModel)
            Tab.LOG -> LogScreen(padding)
        }
    }
}
