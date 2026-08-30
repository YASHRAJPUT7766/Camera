package com.testlab.camerasec.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.LogEntry
import com.testlab.camerasec.log.SecurityTestLog

private fun categoryColor(category: LogCategory): Color = when (category) {
    LogCategory.PERMISSION -> Color(0xFF6A1B9A)
    LogCategory.CAMERA -> Color(0xFFD32F2F)
    LogCategory.LIFECYCLE -> Color(0xFF1565C0)
    LogCategory.STREAMING -> Color(0xFFEF6C00)
    LogCategory.CAMERA_SWITCH -> Color(0xFF00838F)
    LogCategory.CONNECTION -> Color(0xFF2E7D32)
}

@Composable
fun LogScreen(paddingValues: PaddingValues) {
    val entries by SecurityTestLog.entries.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Security Test Log", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { SecurityTestLog.clear() }) {
                Text("Clear")
            }
        }
        Text(
            "Local, in-memory only. Nothing here is uploaded anywhere; it clears when the app process ends.",
            style = MaterialTheme.typography.bodyLarge
        )

        if (entries.isEmpty()) {
            Text("No events yet. Use the other tabs to run tests — every permission check, camera " +
                "start/stop, lifecycle change, streaming event, and dashboard connection will appear here.")
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries.reversed()) { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(categoryColor(entry.category).copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                entry.category.name,
                color = categoryColor(entry.category),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Column {
            Text(entry.message, style = MaterialTheme.typography.bodyLarge)
            Text(
                entry.formattedTime(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}
