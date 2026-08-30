package com.testlab.camerasec.ui.screens

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.testlab.camerasec.MainViewModel
import com.testlab.camerasec.camera.CameraRunState
import com.testlab.camerasec.ui.theme.ActiveRed

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPreviewScreen(paddingValues: PaddingValues, viewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val runState by viewModel.cameraController.runState.collectAsState()
    val lensFacing by viewModel.cameraController.lensFacing.collectAsState()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Camera Preview", style = MaterialTheme.typography.titleLarge)

        if (cameraPermissionState.status !is PermissionStatus.Granted) {
            WarningBanner("Camera permission is not granted. Go to the Permission tab to enable it before starting the preview.")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.TopStart
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView = it }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (runState == CameraRunState.RUNNING) {
                Box(modifier = Modifier.padding(12.dp)) {
                    ActiveIndicator(label = "CAMERA ACTIVE", color = ActiveRed)
                }
            }
        }

        Text(
            text = "Android's own privacy indicator (the green dot in the status bar) will appear " +
                "whenever the camera is actually capturing frames — this app does not hide, cover, " +
                "or suppress it in any way.",
            style = MaterialTheme.typography.bodyLarge
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val pv = previewView
                    if (pv != null && cameraPermissionState.status is PermissionStatus.Granted) {
                        viewModel.startPreview(lifecycleOwner, pv)
                    } else if (cameraPermissionState.status !is PermissionStatus.Granted) {
                        cameraPermissionState.launchPermissionRequest()
                    }
                },
                enabled = runState != CameraRunState.RUNNING,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors()
            ) {
                Icon2(Icons.Filled.PlayArrow)
                Text(" Start Camera")
            }

            OutlinedButton(
                onClick = { viewModel.stopPreview("Stop requested by user") },
                enabled = runState == CameraRunState.RUNNING,
                modifier = Modifier.weight(1f)
            ) {
                Icon2(Icons.Filled.Stop)
                Text(" Stop Camera")
            }
        }

        OutlinedButton(
            onClick = {
                val pv = previewView
                if (pv != null) viewModel.switchCamera(lifecycleOwner, pv)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon2(Icons.Filled.FlipCameraAndroid)
            Text(" Switch to ${if (lensFacing.name == "BACK") "Front" else "Back"} Camera")
        }

        StatusRow(runState)
    }
}

@Composable
private fun Icon2(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    androidx.compose.material3.Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun StatusRow(runState: CameraRunState) {
    val (label, color) = when (runState) {
        CameraRunState.RUNNING -> "Running" to Color(0xFF2E7D32)
        CameraRunState.STARTING -> "Starting…" to Color(0xFFF9A825)
        CameraRunState.ERROR -> "Error" to Color(0xFFD32F2F)
        CameraRunState.STOPPED -> "Stopped" to Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Camera state:", fontWeight = FontWeight.SemiBold)
        StatusChip(label = label, active = runState == CameraRunState.RUNNING, activeColor = color, inactiveColor = color)
    }
}
