package org.siloserver.silo.common.diagnostics

import android.content.res.Configuration
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.network.TokenManager

private val DIAGNOSTICS_DATA_STORE = named("diagnostics-data-store")
private val DIAGNOSTICS_SCOPE = named("diagnostics-scope")

val diagnosticsModule = module {
    single<DataStore<Preferences>>(DIAGNOSTICS_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("silo_diagnostics") },
        )
    }
    single<PendingReportStore> { FilePendingReportStore(androidContext().noBackupFilesDir) }
    single<DiagnosticsLogBuffer> { LogRing() }
    single { DiagnosticsFileLogger(androidContext().noBackupFilesDir) }

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
            consentProvider = get(),
        )
    }

    single<DiagnosticsDeviceProbe> { AndroidDiagnosticsDeviceProbe(androidContext(), get(), get()) }
    single { DeviceSnapshotCollector(get()) }
    single { DeviceSnapshotCache() }
    single { androidExitReportEnvironment(androidContext(), get()) }
    single { FileJvmCrashMarkerSource(androidContext().noBackupFilesDir) }
    single<AndroidExitInfoSource> { FrameworkAndroidExitInfoSource(androidContext()) }
    single<ProcessStateSummaryPublisher> { AndroidProcessStateSummaryPublisher(androidContext()) }
    single { DiagnosticsRunLedger(androidContext().noBackupFilesDir, get()) }
    single<DiagnosticsCaptureController> {
        FileDiagnosticsCaptureController(
            logBuffer = get(),
            fileLogger = get(),
            reports = get(),
            deviceSnapshots = get(),
            deviceSnapshotCache = get(),
            environment = get(),
        )
    }
    single<DiagnosticsRuntimePublisher> {
        DefaultDiagnosticsRuntimePublisher(
            ledger = get(),
            logBuffer = get(),
            deviceSnapshots = get(),
            deviceSnapshotCache = get(),
            redactionTokens = get(),
        )
    }
    single<DiagnosticsIncidentCollector> {
        val source = get<AndroidExitInfoSource>()
        val ledger = get<DiagnosticsRunLedger>()
        val reports = get<PendingReportStore>()
        val markers = get<FileJvmCrashMarkerSource>()
        val environment = get<ExitReportEnvironment>()
        val cache = get<DeviceSnapshotCache>()
        val tokenProvider = get<DiagnosticsRedactionTokenProvider>()
        DiagnosticsIncidentCollector { context, consent ->
            val tokens = runCatching { tokenProvider.tokens() }.getOrDefault(emptyList())
            ExitInfoCollector(
                source = source,
                ledger = ledger,
                reports = reports,
                markers = markers,
                environment = environment,
                deviceSnapshotBytes = cache::currentBytes,
                noticeVersion = { context.noticeVersion },
                consentMode = {
                    when (consent) {
                        DiagnosticsConsentMode.ASK ->
                            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT
                        DiagnosticsConsentMode.ALWAYS ->
                            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.ALWAYS
                        DiagnosticsConsentMode.NEVER ->
                            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT
                    }
                },
                redactionTokens = { tokens },
            ).collect()
        }
    }
    single<DiagnosticsBindingPurger> {
        val reports = get<PendingReportStore>()
        val capture = get<DiagnosticsCaptureController>()
        val ledger = get<DiagnosticsRunLedger>()
        val markers = get<FileJvmCrashMarkerSource>()
        DiagnosticsBindingPurger { binding ->
            runCatching { capture.purge(binding) }
            runCatching { reports.purge(binding) }
            runCatching { ledger.purge(binding) }
            runCatching { markers.purge(binding) }
        }
    }
    single { DiagnosticsSettingsStore(get(DIAGNOSTICS_DATA_STORE), get()) }
    single<DiagnosticsUploadConsentProvider> {
        val settings = get<DiagnosticsSettingsStore>()
        DiagnosticsUploadConsentProvider { binding, noticeVersion ->
            settings.consent(binding, noticeVersion).mode
        }
    }
    single<DiagnosticsSentRecorder> {
        val settings = get<DiagnosticsSettingsStore>()
        DiagnosticsSentRecorder(settings::recordSent)
    }
    single<DiagnosticsUploadScheduler> {
        val context = androidContext()
        DiagnosticsUploadScheduler { reportId -> DiagnosticsUploadWorker.enqueue(context, reportId) }
    }
    single<CoroutineScope>(DIAGNOSTICS_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<DiagnosticsCoordinator> {
        DefaultDiagnosticsCoordinator(
            scope = get(DIAGNOSTICS_SCOPE),
            identity = get(),
            identityTransitions = get(),
            settings = get(),
            reports = get(),
            capture = get(),
            uploader = get(),
            uploadScheduler = get(),
            runtimePublisher = get(),
            incidentCollector = get(),
        )
    }
}

@Suppress("DEPRECATION")
private fun androidExitReportEnvironment(
    context: android.content.Context,
    probe: DiagnosticsDeviceProbe,
): ExitReportEnvironment {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val identity = probe.identity()
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val platform = if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
        DiagnosticsPlatform.ANDROID_TV
    } else {
        DiagnosticsPlatform.ANDROID
    }
    val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode.toString()
    } else {
        packageInfo.versionCode.toString()
    }
    return ExitReportEnvironment(
        appVersion = packageInfo.versionName ?: "unknown",
        appBuild = build,
        platform = platform,
        osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" },
        deviceSummary = DiagnosticsDeviceSummary(
            manufacturer = identity.manufacturer,
            model = identity.model,
            os = "Android ${identity.osRelease}",
            formFactor = identity.formFactor,
        ),
    )
}
