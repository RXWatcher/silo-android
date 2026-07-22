package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.diagnosticsProfileScope

interface DiagnosticsApi {
    suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse>

    suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
    ): ApiResult<DiagnosticsUploadResponse>
}

class DefaultDiagnosticsApi(
    private val client: HttpClient,
) : DiagnosticsApi {
    override suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse> = safeApiCall {
        client.get("/api/v1/diagnostics/status")
    }

    override suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
    ): ApiResult<DiagnosticsUploadResponse> = safeApiCall {
        client.post("/api/v1/diagnostics/reports") {
            diagnosticsProfileScope(capturedProfileId)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "manifest",
                            value = manifestJson,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                                append(HttpHeaders.ContentDisposition, "filename=manifest.json")
                            },
                        )
                        append(
                            key = "bundle",
                            value = bundleBytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, "application/gzip")
                                append(HttpHeaders.ContentDisposition, "filename=bundle.tar.gz")
                            },
                        )
                    },
                ),
            )
        }
    }
}
