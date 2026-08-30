package com.testlab.camerasec.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testlab.camerasec.MainViewModel
import com.testlab.camerasec.camera.CameraRunState
import com.testlab.camerasec.network.LOCAL_STREAM_PORT
import com.testlab.camerasec.ui.theme.ActiveRed
import com.testlab.camerasec.ui.theme.OkGreen
import com.testlab.camerasec.ui.theme.WarnAmber
import java.io.IOException

@Composable
fun StreamingScreen(paddingValues: PaddingValues, viewModel: MainViewModel) {
    val context = LocalContext.current
    val localIp by viewModel.localIp.collectAsState()
    val serverRunning by viewModel.serverRunning.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val cameraRunState by viewModel.cameraController.runState.collectAsState()
    val remoteCaptureEnabled by viewModel.remoteCaptureEnabled.collectAsState()
    val lastRemoteEvent by viewModel.lastRemoteEvent.collectAsState()
    val pairingCode by viewModel.pairingCode.collectAsState()
    val pairingActive by viewModel.pairingActive.collectAsState()
    val liveActive by viewModel.liveActive.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshLocalIp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Remote Test Streaming", style = MaterialTheme.typography.titleLarge)

        if (serverRunning || pairingActive) {
            ActiveIndicator(
                label = if (liveActive) "STREAMING ACTIVE (LIVE)" else "STREAMING ACTIVE",
                color = ActiveRed
            )
        }

        // Visible on-device banner every time a dashboard (LAN or Firebase) triggers an action.
        // This is the anti-stealth requirement made concrete: whoever is holding this phone
        // always sees when a remote action happened, right when it happens.
        lastRemoteEvent?.let { event ->
            Card(colors = CardDefaults.cardColors(containerColor = WarnAmber.copy(alpha = 0.22f))) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("📡", style = MaterialTheme.typography.titleLarge)
                    Text(event.message, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // --- Master remote-capture gate, shared by BOTH the LAN dashboard and the Firebase dashboard ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = (if (remoteCaptureEnabled) ActiveRed else MaterialTheme.colorScheme.surfaceVariant)
                    .copy(alpha = if (remoteCaptureEnabled) 0.12f else 1f)
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow Remote Capture", fontWeight = FontWeight.Bold)
                        Text(
                            "When ON, any connected dashboard (local Wi-Fi OR internet pairing below) " +
                                "can trigger a real photo/video/live capture. Off by default — turn this " +
                                "on only while you're actively testing.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = remoteCaptureEnabled,
                        onCheckedChange = { viewModel.setRemoteCaptureEnabled(it) }
                    )
                }
                if (remoteCaptureEnabled) {
                    Text(
                        "⚠ Remote capture is ON. Turn this off when you're done testing.",
                        color = ActiveRed,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        HorizontalDivider()
        Text("Option A — Internet (works on mobile data, from anywhere)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Uses Firebase as a relay, so the dashboard does NOT need to be on the same Wi-Fi. " +
                "Start a session below to get a pairing code, then enter that code into your " +
                "Netlify-hosted dashboard.",
            style = MaterialTheme.typography.bodyLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pairingActive && pairingCode != null) {
                    Text("Pairing code:", fontWeight = FontWeight.Bold)
                    Text(
                        pairingCode ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        color = OkGreen
                    )
                    Text(
                        "Enter this code in your dashboard. It stays active until you press End Session below.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    StatusChip("Live view ${if (liveActive) "ON" else "OFF"}", active = liveActive, activeColor = ActiveRed)
                } else {
                    Text("No active internet pairing session.", style = MaterialTheme.typography.bodyLarge)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.startFirebasePairing() },
                        enabled = !pairingActive && cameraRunState == CameraRunState.RUNNING,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Session")
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopFirebasePairing() },
                        enabled = pairingActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("End Session")
                    }
                }
                if (cameraRunState != CameraRunState.RUNNING) {
                    WarningBanner("Start the camera from the Preview tab first.")
                }
            }
        }

        HorizontalDivider()
        Text("Option B — Local Wi-Fi only (no internet used)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(
            "The device serves its own dashboard directly over your Wi-Fi network. The browser " +
                "must be on the SAME Wi-Fi network as this phone. No internet, no cloud relay.",
            style = MaterialTheme.typography.bodyLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device address on this Wi-Fi network:", fontWeight = FontWeight.Bold)
                Text(
                    text = if (localIp != null) "http://$localIp:$LOCAL_STREAM_PORT" else "Not connected to a local network",
                    style = MaterialTheme.typography.titleLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusChip("Camera ${cameraRunState.name}", active = cameraRunState == CameraRunState.RUNNING)
                    StatusChip(
                        if (serverRunning) "LAN Streaming ON" else "LAN Streaming OFF",
                        active = serverRunning,
                        activeColor = ActiveRed
                    )
                    StatusChip("$connectedClients client(s)", active = connectedClients > 0)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    viewModel.startStreaming { assetPath -> readAsset(context, assetPath) }
                },
                enabled = !serverRunning && cameraRunState == CameraRunState.RUNNING,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start LAN Streaming")
            }
            OutlinedButton(
                onClick = { viewModel.stopStreaming() },
                enabled = serverRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop LAN Streaming")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quick reference:", fontWeight = FontWeight.Bold)
                Text("• Internet (Firebase): works on mobile data or Wi-Fi, dashboard can be anywhere — needs a pairing code.")
                Text("• Local Wi-Fi: no internet used at all, but dashboard device must share this phone's Wi-Fi.")
                Text("• Either way, nothing is captured unless Allow Remote Capture is ON above.")
            }
        }
    }
}

private fun readAsset(context: android.content.Context, assetPath: String): ByteArray? {
    return try {
        context.assets.open(assetPath).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }
}
