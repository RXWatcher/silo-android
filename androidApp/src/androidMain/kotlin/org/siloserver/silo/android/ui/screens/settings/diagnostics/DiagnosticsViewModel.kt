package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadDecision

data class DiagnosticsPhoneScreenModel(
    val showPending: Boolean,
    val canUpload: Boolean,
    val canDelete: Boolean,
    val canCapture: Boolean,
)

data class DiagnosticsConsentActionModel(
    val requiresConfirmation: Boolean,
)

fun shouldShowDiagnosticsEntry(state: DiagnosticsUiState): Boolean = state.profileEligible

fun diagnosticsPhoneScreenModel(state: DiagnosticsUiState): DiagnosticsPhoneScreenModel =
    DiagnosticsPhoneScreenModel(
        showPending = state.pending.isNotEmpty(),
        canUpload = state.profileEligible && state.availability == DiagnosticsAvailabilityUi.AVAILABLE,
        canDelete = state.profileEligible && state.pending.isNotEmpty(),
        canCapture = state.profileEligible && state.consent != DiagnosticsConsentMode.NEVER,
    )

fun consentActionModel(
    current: DiagnosticsConsentMode,
    requested: DiagnosticsConsentMode,
): DiagnosticsConsentActionModel = DiagnosticsConsentActionModel(
    requiresConfirmation = requested == DiagnosticsConsentMode.ALWAYS && current != DiagnosticsConsentMode.ALWAYS,
)

class DiagnosticsViewModel(
    private val coordinator: DiagnosticsCoordinator,
) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> = coordinator.state

    init {
        coordinator.start()
        viewModelScope.launch { coordinator.refresh() }
    }

    fun refresh() {
        viewModelScope.launch { coordinator.refresh() }
    }

    fun setConsent(mode: DiagnosticsConsentMode) {
        viewModelScope.launch { coordinator.setConsent(mode) }
    }

    fun setDebugLogging(enabled: Boolean) {
        viewModelScope.launch { coordinator.setDebugLogging(enabled) }
    }

    fun captureNow(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch { coordinator.captureNow()?.let(onCreated) }
    }

    fun startTimedCapture() {
        viewModelScope.launch { coordinator.startTimedCapture() }
    }

    fun stopTimedCapture(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch { coordinator.stopTimedCapture()?.let(onCreated) }
    }

    fun cancelTimedCapture() {
        viewModelScope.launch { coordinator.cancelTimedCapture() }
    }

    fun upload(reportId: String, onComplete: (DiagnosticsUploadDecision) -> Unit = {}) {
        viewModelScope.launch { onComplete(coordinator.upload(reportId)) }
    }

    fun delete(reportId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            coordinator.delete(reportId)
            onDeleted()
        }
    }

    fun decline(reportId: String) {
        viewModelScope.launch { coordinator.decline(reportId) }
    }
}
