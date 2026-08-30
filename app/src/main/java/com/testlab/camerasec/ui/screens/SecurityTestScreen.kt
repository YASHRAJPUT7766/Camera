package com.testlab.camerasec.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testlab.camerasec.MainViewModel
import com.testlab.camerasec.camera.CameraRunState

private data class TestStep(
    val title: String,
    val instructions: String,
    val expectedReport: String
)

private val testSteps = listOf(
    TestStep(
        title = "1. App in Foreground",
        instructions = "Start the camera on the Preview tab while keeping the app open and visible. " +
            "This is the baseline: normal Android behavior allows camera access here.",
        expectedReport = "\"CAMERA ACTIVE — bound to lifecycle\" appears in the log, and the privacy " +
            "indicator dot is visible in the status bar."
    ),
    TestStep(
        title = "2. App Moved to Background",
        instructions = "With the camera running, press the Home button or switch to another app.",
        expectedReport = "CameraX unbinds when the Activity's lifecycle stops. Expect: " +
            "\"Camera stopped when app entered this state.\" You will NOT see continued frames — " +
            "Android does not allow ordinary foreground apps to keep the camera open once backgrounded."
    ),
    TestStep(
        title = "3. Screen Turned Off",
        instructions = "With the camera running in the foreground, press the device power button to turn off the screen.",
        expectedReport = "The Activity moves through ON_STOP the same as backgrounding. Expect: " +
            "\"Camera stopped when app entered this state.\" The camera does not continue recording " +
            "with the screen off — this app has no wake-lock or screen-off capture path."
    ),
    TestStep(
        title = "4. Camera Permission Revoked",
        instructions = "While the app is open, go to Android Settings → Apps → Camera Security Lab → Permissions " +
            "and turn off Camera. Return to the app.",
        expectedReport = "\"Camera permission unavailable.\" Any active preview session is torn down by the " +
            "OS or fails to rebind on the next start attempt. The Permission tab will show DENIED."
    ),
    TestStep(
        title = "5. App Force-Stopped",
        instructions = "While the camera or streaming is active, force-stop the app from Android Settings → Apps → " +
            "Camera Security Lab → Force stop (or swipe it away from Recent Apps with 'stop apps on swipe' enabled on some OEM skins).",
        expectedReport = "\"Camera service could not continue.\" The entire process is killed by Android — camera, " +
            "preview, and the local streaming server all stop immediately because none of them run outside this app's own process."
    )
)

@Composable
fun SecurityTestScreen(paddingValues: PaddingValues, viewModel: MainViewModel) {
    val runState by viewModel.cameraController.runState.collectAsState()
    val serverRunning by viewModel.serverRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Security Test — Lifecycle & Background", style = MaterialTheme.typography.titleLarge)
        Text(
            "These are manual test procedures for you to run on your own device. The app does not " +
                "attempt to defeat any of these Android restrictions — it reports what actually happens " +
                "so you can confirm the OS is enforcing them correctly.",
            style = MaterialTheme.typography.bodyLarge
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Live state while you test:", fontWeight = FontWeight.Bold)
                Text("Camera run state: ${runState.name}")
                Text("Streaming server: ${if (serverRunning) "RUNNING" else "STOPPED"}")
            }
        }

        testSteps.forEach { step ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(step.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(step.instructions, fontWeight = FontWeight.Normal)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
                        Text(
                            "Expected report: ${step.expectedReport}",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What you will NOT see from this app:", fontWeight = FontWeight.Bold)
                Text("• The camera continuing to run after you leave the foreground.")
                Text("• Any capture happening with the screen off.")
                Text("• Any recovery of camera access after a permission revoke without you granting it again.")
                Text("• Any component surviving a force-stop. There is no background service to survive it.")
            }
        }
    }
}
