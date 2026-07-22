package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DiagnosticsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiagnosticsUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun profileSwitchDuringBuildPreventsPost() = runTest {
        val fixture = fixture()
        fixture.builder.onBuild = {
            fixture.identity.current = fixture.identity.current?.copy(profileId = "other")
        }

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptIdentityChanged, decision)
        assertEquals(0, fixture.api.uploadCalls)
        assertNotNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun consentNoticeChangeDuringBuildPreventsPost() = runTest {
        val fixture = fixture()
        fixture.builder.onBuild = {
            fixture.identity.current = fixture.identity.current?.copy(noticeVersion = 3)
        }

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptInvalid, decision)
        assertEquals(0, fixture.api.uploadCalls)
        val report = assertNotNull(fixture.store.load(fixture.report.id))
        assertEquals(PendingReportStatus.PERMANENT_FAILURE, report.state.status)
        assertEquals("stale_consent", report.state.errorCode)
    }

    @Test
    fun successfulUploadUsesCapturedProfileRecordsHistoryAndDeletesReport() = runTest {
        val fixture = fixture()
        fixture.api.result = ApiResult.Success(DiagnosticsUploadResponse("report-1", "ABC123"))

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), decision)
        assertEquals("profile-1", fixture.api.capturedProfileId)
        assertEquals(listOf("ABC123"), fixture.sent.shortIds)
        assertNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun unsupportedSchemaMarksServerUpdateRequired() = runTest {
        val fixture = fixture()
        fixture.api.result = ApiResult.Error(400, "unsupported_schema", "upgrade")

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptServerUpdateRequired, decision)
        val report = assertNotNull(fixture.store.load(fixture.report.id))
        assertEquals(PendingReportStatus.PERMANENT_FAILURE, report.state.status)
        assertEquals("unsupported_schema", report.state.errorCode)
    }

    @Test
    fun bundleOverServerLimitNeverPosts() = runTest {
        val fixture = fixture(maxBundleBytes = 4)
        fixture.builder.bundleBytes = ByteArray(5)

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptTooLarge, decision)
        assertEquals(0, fixture.api.uploadCalls)
    }

    @Test
    fun everyStableServerErrorHasExplicitPolicy() = runTest {
        val cases = mapOf(
            "busy" to DiagnosticsUploadDecision.KeptRetryable,
            "quota_exceeded" to DiagnosticsUploadDecision.KeptRetryable,
            "rate_limited" to DiagnosticsUploadDecision.KeptRetryable,
            "internal_error" to DiagnosticsUploadDecision.KeptRetryable,
            "too_large" to DiagnosticsUploadDecision.KeptTooLarge,
            "unsupported_schema" to DiagnosticsUploadDecision.KeptServerUpdateRequired,
            "storage_unavailable" to DiagnosticsUploadDecision.KeptUnavailable,
            "disabled" to DiagnosticsUploadDecision.KeptUnavailable,
            "diagnostics_disabled" to DiagnosticsUploadDecision.KeptUnavailable,
            "destination_mismatch" to DiagnosticsUploadDecision.KeptIdentityChanged,
            "profile_mismatch" to DiagnosticsUploadDecision.KeptIdentityChanged,
            "child_profile_forbidden" to DiagnosticsUploadDecision.KeptIdentityChanged,
            "invalid_bundle" to DiagnosticsUploadDecision.KeptInvalid,
            "invalid_archive" to DiagnosticsUploadDecision.KeptInvalid,
            "invalid_manifest" to DiagnosticsUploadDecision.KeptInvalid,
            "archive_mismatch" to DiagnosticsUploadDecision.KeptInvalid,
            "stale_report" to DiagnosticsUploadDecision.KeptInvalid,
            "stale_consent" to DiagnosticsUploadDecision.KeptInvalid,
            "unauthorized" to DiagnosticsUploadDecision.KeptInvalid,
            "api_key_not_allowed" to DiagnosticsUploadDecision.KeptInvalid,
            "forbidden" to DiagnosticsUploadDecision.KeptInvalid,
        )

        cases.forEach { (code, expected) ->
            val fixture = fixture()
            fixture.api.result = ApiResult.Error(if (code in setOf("busy", "internal_error")) 503 else 400, code, "message")

            assertEquals(expected, fixture.uploader.upload(fixture.report.id), code)
            val state = assertNotNull(fixture.store.load(fixture.report.id)).state
            val expectedStatus = if (expected == DiagnosticsUploadDecision.KeptRetryable) {
                PendingReportStatus.RETRYABLE
            } else {
                PendingReportStatus.PERMANENT_FAILURE
            }
            assertEquals(expectedStatus, state.status, code)
            assertEquals(code, state.errorCode, code)
        }
    }

    private fun fixture(maxBundleBytes: Long = 1_024 * 1_024): Fixture {
        val store = FilePendingReportStore(
            noBackupFilesDir = temporaryFolder.newFolder(),
            nowMs = { CAPTURED_AT },
        )
        val report = store.save(
            PendingReportCapture(
                binding = PENDING_BINDING,
                manifest = manifest(),
                artifacts = mapOf("device.json" to "{}".encodeToByteArray()),
                fingerprint = "fingerprint",
                capturedAtEpochMs = CAPTURED_AT,
            ),
        )
        val identity = FakeIdentityResolver(context(maxBundleBytes))
        val builder = FakeBundleBuilder()
        val api = FakeDiagnosticsApi()
        val sent = FakeSentRecorder()
        val uploader = DefaultDiagnosticsUploader(
            reports = store,
            identity = identity,
            bundleBuilder = builder,
            api = api,
            redactionTokens = DiagnosticsRedactionTokenProvider { listOf("secret-token") },
            sentRecorder = sent,
            nowMs = { CAPTURED_AT + 1_000 },
        )
        return Fixture(store, report, identity, builder, api, sent, uploader)
    }

    private fun context(maxBundleBytes: Long) = DiagnosticsCaptureContext(
        binding = BINDING,
        profileId = "profile-1",
        profileEligible = true,
        noticeVersion = 2,
        status = DiagnosticsAvailabilityStatus.AVAILABLE,
        ownershipGeneration = 7,
        acceptedSchemaVersions = setOf(1),
        maxBundleBytes = maxBundleBytes,
        maxManifestBytes = 64 * 1_024,
    )

    private fun manifest() = DiagnosticsManifest(
        schemaVersion = 1,
        report = DiagnosticsReport(
            type = DiagnosticsReportType.MANUAL,
            capturedAt = "2026-07-22T00:00:00Z",
            captureSessionId = "capture-1",
            appVersion = "1.0",
            appBuild = "1",
            platform = DiagnosticsPlatform.ANDROID_TV,
            osVersion = "36",
            profileId = "profile-1",
        ),
        destination = DiagnosticsDestination("server-1"),
        consent = DiagnosticsConsent(DiagnosticsConsentMode.MANUAL, 2),
        deviceSummary = DiagnosticsDeviceSummary("NVIDIA", "Shield", "Android 36", "tv"),
        playbackSessionIds = emptyList(),
        logSummary = DiagnosticsLogSummary(0, 0, 0, listOf(DiagnosticsLogCategory.OTHER), false),
        archive = DiagnosticsArchive(listOf("manifest.json", "device.json"), 0, 0, "0".repeat(64)),
    )

    private class FakeIdentityResolver(var current: DiagnosticsCaptureContext?) : DiagnosticsIdentityResolver {
        override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? = current
    }

    private class FakeBundleBuilder : DiagnosticsBundleBuilder {
        var onBuild: () -> Unit = {}
        var bundleBytes = byteArrayOf(1, 2, 3)
        override fun build(report: PendingReport, redactionTokens: List<String>): DiagnosticsBundle {
            onBuild()
            return DiagnosticsBundle(report.manifest, "{}".encodeToByteArray(), bundleBytes)
        }
    }

    private class FakeDiagnosticsApi : DiagnosticsApi {
        var result: ApiResult<DiagnosticsUploadResponse> = ApiResult.NetworkError(IllegalStateException("offline"))
        var uploadCalls = 0
        var capturedProfileId: String? = null
        override suspend fun getStatus() = error("unused")
        override suspend fun upload(
            manifestJson: ByteArray,
            bundleBytes: ByteArray,
            capturedProfileId: String?,
        ): ApiResult<DiagnosticsUploadResponse> {
            uploadCalls += 1
            this.capturedProfileId = capturedProfileId
            return result
        }
    }

    private class FakeSentRecorder : DiagnosticsSentRecorder {
        val shortIds = mutableListOf<String>()
        override suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long) {
            shortIds += shortId
        }
    }

    private data class Fixture(
        val store: FilePendingReportStore,
        val report: PendingReport,
        val identity: FakeIdentityResolver,
        val builder: FakeBundleBuilder,
        val api: FakeDiagnosticsApi,
        val sent: FakeSentRecorder,
        val uploader: DefaultDiagnosticsUploader,
    )

    private companion object {
        val BINDING = DiagnosticsBinding("server-1", "user-1")
        val PENDING_BINDING = PendingReportBinding("server-1", "user-1", "profile-1", 7)
        const val CAPTURED_AT = 1_700_000_000_000L
    }
}
