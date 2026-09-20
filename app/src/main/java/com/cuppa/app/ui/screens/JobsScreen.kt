package com.cuppa.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuppa.app.data.JobHistoryEntry
import com.cuppa.app.viewmodel.JobsViewModel
import com.cuppa.cups.PrintJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JobsScreen — live print queue plus persisted job history.
 *
 * The native server's own job queue is purely in-memory (CupsServer::mJobs in cups_server.cpp)
 * and is gone the moment the process restarts, so completed/failed jobs are also recorded to
 * SharedPreferences via CupsRepository.jobHistory the first time each one reaches a terminal
 * state — that's what keeps this screen from always being empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    viewModel: JobsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Print Jobs",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (uiState.activeJobs.isEmpty() && uiState.history.isEmpty()) {
            EmptyJobsState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.activeJobs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Active (${uiState.activeJobs.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(uiState.activeJobs, key = { "active-${it.jobId}" }) { job ->
                        ActiveJobCard(job)
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                if (uiState.history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Recent",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(onClick = { viewModel.clearHistory() }) {
                                Text("Clear all")
                            }
                        }
                    }
                    // Newest first, grouped under a day heading so a long list stays scannable.
                    val byDay = uiState.history.groupBy { dayLabel(it.finishedAt) }
                    for ((day, entries) in byDay) {
                        item(key = "day-$day") {
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        items(entries, key = { "history-${it.jobId}-${it.finishedAt}" }) { entry ->
                            HistoryJobCard(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveJobCard(job: PrintJob) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(Icons.Outlined.Sync, MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.jobName.ifBlank { "Untitled Job" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = "${job.printerUri} · ${job.status.displayName} · ${formatBytes(job.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun HistoryJobCard(entry: JobHistoryEntry) {
    val (icon, tint) = when (entry.state) {
        9 -> Icons.Outlined.CheckCircle to Color(0xFF2E7D32) // Completed
        7 -> Icons.Outlined.PauseCircle to MaterialTheme.colorScheme.outline // Canceled
        else -> Icons.Outlined.Error to MaterialTheme.colorScheme.error // Aborted / other
    }
    val stateLabel = when (entry.state) {
        9 -> "Completed"
        7 -> "Canceled"
        8 -> "Failed"
        else -> "Finished"
    }
    val failed = entry.state == 8

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(icon, tint)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.jobName.ifBlank { "Untitled Job" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = entry.printerName.ifBlank { entry.printerUri },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) {
                    Text(
                        text = "The printer did not accept this job. Check that it is on, has paper and is connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatTime(entry.finishedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EmptyJobsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "No print jobs yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Print something from a phone, laptop or tablet and it will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
}

private fun dayLabel(millis: Long): String {
    if (millis <= 0L) return "Earlier"
    val cal = java.util.Calendar.getInstance()
    fun dayNumber(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    val today = dayNumber(cal)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = dayNumber(cal)
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return when (dayNumber(then)) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(millis))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024) "${kb.toInt()} KB" else "${"%.1f".format(kb / 1024.0)} MB"
}
