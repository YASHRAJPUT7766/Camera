package com.testlab.camerasec.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import com.testlab.camerasec.ui.theme.ActiveRed
import com.testlab.camerasec.ui.theme.OkGreen

/**
 * Camera Permission Test screen.
 *
 * This screen only ever calls the standard Android runtime permission API
 * (via Accompanist's thin wrapper around ActivityResultContracts.RequestPermission).
 * There is no reflection, no hidden API, no attempt to read or set the
 * permission grant state directly. Denial is treated as a normal, final
 * outcome that the user can only change via Android's own Settings screen —
 * which is exactly what the "Open App Settings" button links to.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(cameraPermissionState.status) {
        val status = cameraPermissionState.status
        val message = when {
            status is PermissionStatus.Granted -> "Camera permission is GRANTED"
            status is PermissionStatus.Denied && status.shouldShowRationale ->
                "Camera permission DENIED (user can be asked again)"
            else -> "Camera permission DENIED (user must enable it from Settings)"
        }
        SecurityTestLog.log(LogCategory.PERMISSION, message)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Camera Permission Test",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "This screen uses Android's standard runtime permission dialog only. " +
                "No bypass, no silent grant — if it says Denied, the camera genuinely cannot be used " +
                "until you allow it here or in Settings.",
            style = MaterialTheme.typography.bodyLarge
        )

        val granted = cameraPermissionState.status is PermissionStatus.Granted

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = (if (granted) OkGreen else ActiveRed).copy(alpha = 0.12f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (granted) OkGreen else ActiveRed,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = if (granted) "GRANTED" else "DENIED",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (granted) OkGreen else ActiveRed,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (granted)
                        "The app is currently allowed to access the camera."
                    else
                        "The app currently cannot access the camera. This is Android enforcing your choice."
                )
            }
        }

        if (!granted) {
            Button(
                onClick = { cameraPermissionState.launchPermissionRequest() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request Camera Permission")
            }
        }

        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
                SecurityTestLog.log(LogCategory.PERMISSION, "Opened Android App Settings for this app")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text("Open App Permission Settings")
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), border = CardDefaults.outlinedCardBorder()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What this tests", fontWeight = FontWeight.Bold)
                Text("• That Android's permission prompt appears normally, not silently.")
                Text("• That denial actually blocks camera use elsewhere in this app.")
                Text("• That you can always reach the OS settings page to change your decision.")
            }
        }
    }
}
