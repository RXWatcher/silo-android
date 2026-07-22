package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi

@Composable
fun TvDiagnosticsReportScreen(
    reportId: String,
    onBack: () -> Unit,
    viewModel: TvDiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val report = state.pending.firstOrNull { it.id == reportId }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    TvDiagnosticsPage(title = "Report details") {
        if (report == null) {
            Text("This report is no longer on this device.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(report.type.tvDisplayName(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(report.capturedAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    TvReportLine("Evidence", tvFormatBytes(report.evidenceBytes))
                    TvReportLine("Destination", report.destinationServerInstanceId)
                    TvReportLine("Captured profile", report.capturedProfileId ?: "Account scoped")
                    TvReportLine("Expires", tvFormatDate(report.expiresAtEpochMs))
                    TvReportLine("Upload state", report.uploadStatus.name.lowercase().replace('_', ' '))
                    report.uploadErrorCode?.let { TvReportLine("Last error", it) }
                }
                item {
                    TvDiagnosticsSection("ARCHIVE ENTRIES") {
                        report.archiveEntries.forEach { entry ->
                            Text(entry, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvDiagnosticsAction(
                            label = "Send",
                            enabled = state.availability == DiagnosticsAvailabilityUi.AVAILABLE,
                            onClick = { viewModel.upload(report.id) },
                            modifier = Modifier.weight(1f),
                        )
                        TvDiagnosticsAction(
                            label = "Delete",
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete && report != null) {
        TvDiagnosticsConfirmation(
            title = "Delete this report?",
            message = "The local evidence will be permanently removed from this device.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.delete(report.id, onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun TvReportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1.8f))
    }
}
