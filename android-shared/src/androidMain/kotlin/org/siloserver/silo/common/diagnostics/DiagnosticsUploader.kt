package org.siloserver.silo.common.diagnostics

import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DiagnosticsApi

sealed interface DiagnosticsUploadDecision {
    data class Uploaded(val shortId: String) : DiagnosticsUploadDecision
    data object KeptRetryable : DiagnosticsUploadDecision
    data object KeptIdentityChanged : DiagnosticsUploadDecision
    data object KeptTooLarge : DiagnosticsUploadDecision
    data object KeptServerUpdateRequired : DiagnosticsUploadDecision
    data object KeptUnavailable : DiagnosticsUploadDecision
    data object KeptInvalid : DiagnosticsUploadDecision
}

fun interface DiagnosticsUploader {
    suspend fun upload(reportId: String): DiagnosticsUploadDecision

    suspend fun uploadAutomatically(reportId: String): DiagnosticsUploadDecision = upload(reportId)
}

fun interface DiagnosticsRedactionTokenProvider {
    suspend fun tokens(): List<String>
}

fun interface DiagnosticsSentRecorder {
    suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long)
}

fun interface DiagnosticsUploadConsentProvider {
    suspend fun consent(binding: DiagnosticsBinding, noticeVersion: Int): DiagnosticsConsentMode
}

class DefaultDiagnosticsUploader(
    private val reports: PendingReportStore,
    private val identity: DiagnosticsIdentityResolver,
    private val bundleBuilder: DiagnosticsBundleBuilder,
    private val api: DiagnosticsApi,
    private val redactionTokens: DiagnosticsRedactionTokenProvider,
    private val sentRecorder: DiagnosticsSentRecorder,
    private val consentProvider: DiagnosticsUploadConsentProvider = DiagnosticsUploadConsentProvider {
            _, _ -> DiagnosticsConsentMode.ASK
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DiagnosticsUploader {
    override suspend fun upload(reportId: String): DiagnosticsUploadDecision =
        upload(reportId, requireAlwaysConsent = false)

    override suspend fun uploadAutomatically(reportId: String): DiagnosticsUploadDecision =
        upload(reportId, requireAlwaysConsent = true)

    private suspend fun upload(
        reportId: String,
        requireAlwaysConsent: Boolean,
    ): DiagnosticsUploadDecision {
        val report = reports.load(reportId) ?: return DiagnosticsUploadDecision.KeptInvalid
        if (!consentAllows(report, requireAlwaysConsent)) return DiagnosticsUploadDecision.KeptUnavailable
        val before = identity.resolve(requirePersistentCapture = true)
            ?: return DiagnosticsUploadDecision.KeptUnavailable
        if (!report.canUploadUnder(before)) return DiagnosticsUploadDecision.KeptIdentityChanged
        if (before.status != DiagnosticsAvailabilityStatus.AVAILABLE) {
            return DiagnosticsUploadDecision.KeptUnavailable
        }
        if (report.manifest.schemaVersion !in before.acceptedSchemaVersions) {
            markPermanent(report.id, "unsupported_schema")
            return DiagnosticsUploadDecision.KeptServerUpdateRequired
        }
        if (report.manifest.consent.noticeVersion != before.noticeVersion) {
            markPermanent(report.id, "stale_consent")
            return DiagnosticsUploadDecision.KeptInvalid
        }

        val tokens = runCatching { redactionTokens.tokens() }.getOrElse {
            markRetryable(report.id, "redaction_tokens_unavailable")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        val bundle = runCatching { bundleBuilder.build(report, tokens) }.getOrElse {
            markPermanent(report.id, "invalid_bundle")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        if (bundle.bytes.size.toLong() > before.maxBundleBytes || bundle.manifestBytes.size.toLong() > before.maxManifestBytes) {
            markPermanent(report.id, "too_large")
            return DiagnosticsUploadDecision.KeptTooLarge
        }

        val after = identity.resolve(requirePersistentCapture = true)
            ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        if (before.identityKey != after.identityKey || !report.canUploadUnder(after)) {
            return DiagnosticsUploadDecision.KeptIdentityChanged
        }
        if (after.status != DiagnosticsAvailabilityStatus.AVAILABLE) {
            return DiagnosticsUploadDecision.KeptUnavailable
        }
        if (report.manifest.schemaVersion !in after.acceptedSchemaVersions) {
            markPermanent(report.id, "unsupported_schema")
            return DiagnosticsUploadDecision.KeptServerUpdateRequired
        }
        if (report.manifest.consent.noticeVersion != after.noticeVersion) {
            markPermanent(report.id, "stale_consent")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        if (!consentAllows(report, requireAlwaysConsent)) return DiagnosticsUploadDecision.KeptUnavailable
        if (
            bundle.bytes.size.toLong() > after.maxBundleBytes ||
            bundle.manifestBytes.size.toLong() > after.maxManifestBytes
        ) {
            markPermanent(report.id, "too_large")
            return DiagnosticsUploadDecision.KeptTooLarge
        }

        val result = runCatching {
            api.upload(bundle.manifestBytes, bundle.bytes, report.binding.profileId)
        }.getOrElse {
            markRetryable(report.id, "network")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        return when (result) {
            is ApiResult.Success -> {
                reports.delete(report.id)
                runCatching { sentRecorder.record(report.binding.binding, result.data.shortId, nowMs()) }
                DiagnosticsUploadDecision.Uploaded(result.data.shortId)
            }
            is ApiResult.NetworkError -> {
                markRetryable(report.id, "network")
                DiagnosticsUploadDecision.KeptRetryable
            }
            is ApiResult.Error -> mapServerError(report.id, result)
        }
    }

    private fun mapServerError(reportId: String, error: ApiResult.Error): DiagnosticsUploadDecision {
        val code = error.error.ifBlank { "http_${error.code}" }
        val decision = when (code) {
            "busy", "quota", "quota_exceeded", "rate_limited", "rate_limit", "rate_limit_exceeded", "internal_error" ->
                DiagnosticsUploadDecision.KeptRetryable
            "too_large" -> DiagnosticsUploadDecision.KeptTooLarge
            "unsupported_schema" -> DiagnosticsUploadDecision.KeptServerUpdateRequired
            "storage_unavailable", "disabled", "diagnostics_disabled" -> DiagnosticsUploadDecision.KeptUnavailable
            "destination_mismatch", "profile_mismatch", "child_profile_forbidden" ->
                DiagnosticsUploadDecision.KeptIdentityChanged
            "invalid_bundle", "invalid_archive", "invalid_manifest", "archive_mismatch", "stale_report",
            "stale_consent", "unauthorized", "api_key_not_allowed", "forbidden",
            -> DiagnosticsUploadDecision.KeptInvalid
            else -> if (error.code == 429 || error.code >= 500) {
                DiagnosticsUploadDecision.KeptRetryable
            } else {
                DiagnosticsUploadDecision.KeptInvalid
            }
        }
        if (decision == DiagnosticsUploadDecision.KeptRetryable) {
            markRetryable(reportId, code)
        } else {
            markPermanent(reportId, code)
        }
        return decision
    }

    private fun markRetryable(reportId: String, code: String) {
        runCatching { reports.markState(reportId, PendingReportStatus.RETRYABLE, code) }
    }

    private fun markPermanent(reportId: String, code: String) {
        runCatching { reports.markState(reportId, PendingReportStatus.PERMANENT_FAILURE, code) }
    }

    private suspend fun consentAllows(report: PendingReport, requireAlways: Boolean): Boolean {
        val mode = runCatching {
            consentProvider.consent(report.binding.binding, report.manifest.consent.noticeVersion)
        }.getOrNull() ?: return false
        return mode != DiagnosticsConsentMode.NEVER && (!requireAlways || mode == DiagnosticsConsentMode.ALWAYS)
    }
}

private fun PendingReport.canUploadUnder(context: DiagnosticsCaptureContext): Boolean =
    context.profileEligible &&
        binding.matches(context) &&
        manifest.destination.serverInstanceId == context.binding.serverInstanceId &&
        manifest.report.profileId == context.profileId
