package org.siloserver.silo.common.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.siloserver.silo.network.TokenManager

private val DIAGNOSTICS_DATA_STORE = named("diagnostics-data-store")

val diagnosticsModule = module {
    single<DataStore<Preferences>>(DIAGNOSTICS_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("silo_diagnostics") },
        )
    }
    single<PendingReportStore> { FilePendingReportStore(androidContext().noBackupFilesDir) }
    single<DiagnosticsBindingPurger> {
        val reports = get<PendingReportStore>()
        DiagnosticsBindingPurger { binding -> reports.purge(binding) }
    }
    single { DiagnosticsSettingsStore(get(DIAGNOSTICS_DATA_STORE), get()) }
    single<DiagnosticsSentRecorder> {
        val settings = get<DiagnosticsSettingsStore>()
        DiagnosticsSentRecorder(settings::recordSent)
    }

    single<DiagnosticsSavedServerProvider> { RegistryDiagnosticsSavedServerProvider(get()) }
    single<DiagnosticsStatusProvider> { ApiDiagnosticsStatusProvider(get()) }
    single<DiagnosticsAccountProvider> { RepositoryDiagnosticsAccountProvider(get()) }
    single<DiagnosticsProfileProvider> { RepositoryDiagnosticsProfileProvider(get()) }
    single<DiagnosticsIdentityResolver> {
        DefaultDiagnosticsIdentityResolver(
            tokenManager = get(),
            identityTransitions = get(),
            savedServerProvider = get(),
            statusProvider = get(),
            accountProvider = get(),
            profileProvider = get(),
        )
    }

    single<DiagnosticsBundleBuilder> { FileDiagnosticsBundleBuilder() }
    single<DiagnosticsRedactionTokenProvider> {
        val tokenManager = get<TokenManager>()
        DiagnosticsRedactionTokenProvider {
            listOfNotNull(
                tokenManager.getAccessToken(),
                tokenManager.getRefreshToken(),
                tokenManager.getProfileToken(),
            ).filter(String::isNotBlank)
        }
    }
    single<DiagnosticsUploader> {
        DefaultDiagnosticsUploader(
            reports = get(),
            identity = get(),
            bundleBuilder = get(),
            api = get(),
            redactionTokens = get(),
            sentRecorder = get(),
        )
    }

    single<DiagnosticsDeviceProbe> { AndroidDiagnosticsDeviceProbe(androidContext(), get(), get()) }
    single { DeviceSnapshotCollector(get()) }
    single { DeviceSnapshotCache() }
    single { FileJvmCrashMarkerSource(androidContext().noBackupFilesDir) }
    single<AndroidExitInfoSource> { FrameworkAndroidExitInfoSource(androidContext()) }
    single<ProcessStateSummaryPublisher> { AndroidProcessStateSummaryPublisher(androidContext()) }
    single { DiagnosticsRunLedger(androidContext().noBackupFilesDir, get()) }
}
