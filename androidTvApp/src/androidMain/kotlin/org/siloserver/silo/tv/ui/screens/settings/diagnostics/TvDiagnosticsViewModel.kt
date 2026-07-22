package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState

enum class TvDiagnosticsPromptFocus { DONT_SEND, REVIEW, SEND, ALWAYS_SEND }

data class TvDiagnosticsPromptModel(val initialFocus: TvDiagnosticsPromptFocus)

data class TvDiagnosticsConsentAction(val requiresConfirmation: Boolean)

data class TvDiagnosticsScreenModel(
    val showPending: Boolean,
    val canUpload: Boolean,
    val canDelete: Boolean,
    val canCapture: Boolean,
)

fun tvDiagnosticsPromptModel(prompt: DiagnosticsPrompt): TvDiagnosticsPromptModel {
    require(prompt.reportId.isNotBlank())
    return TvDiagnosticsPromptModel(TvDiagnosticsPromptFocus.DONT_SEND)
}

fun tvDiagnosticsConsentAction(
    current: DiagnosticsConsentMode,
    requested: DiagnosticsConsentMode,
): TvDiagnosticsConsentAction = TvDiagnosticsConsentAction(
    requiresConfirmation = requested == DiagnosticsConsentMode.ALWAYS && current != DiagnosticsConsentMode.ALWAYS,
)

fun tvDiagnosticsScreenModel(state: DiagnosticsUiState): TvDiagnosticsScreenModel =
    TvDiagnosticsScreenModel(
        showPending = state.pending.isNotEmpty(),
        canUpload = state.profileEligible && state.availability == DiagnosticsAvailabilityUi.AVAILABLE,
        canDelete = state.profileEligible && state.pending.isNotEmpty(),
        canCapture = state.profileEligible && state.consent != DiagnosticsConsentMode.NEVER,
    )

class TvDiagnosticsViewModel(
    private val coordinator: DiagnosticsCoordinator,
) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> = coordinator.state

    init {
        coordinator.start()
        viewModelScope.launch { coordinator.refresh() }
    }

    fun setConsent(mode: DiagnosticsConsentMode) {
        viewModelScope.launch { coordinator.setConsent(mode) }
    }

    fun setDebugLogging(enabled: Boolean) {
        viewModelScope.launch { coordinator.setDebugLogging(enabled) }
    }

    fun captureNow(onCreated: (String) -> Unit) {
        viewModelScope.launch { coordinator.captureNow()?.let(onCreated) }
    }

    fun startTimedCapture() {
        viewModelScope.launch { coordinator.startTimedCapture() }
    }

    fun stopTimedCapture(onCreated: (String) -> Unit) {
        viewModelScope.launch { coordinator.stopTimedCapture()?.let(onCreated) }
    }

    fun cancelTimedCapture() {
        viewModelScope.launch { coordinator.cancelTimedCapture() }
    }

    fun upload(reportId: String) {
        viewModelScope.launch { coordinator.upload(reportId) }
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
