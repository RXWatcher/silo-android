package org.siloserver.silo.common.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DiagnosticsBinding(
    val serverInstanceId: String,
    val accountUserId: String,
) {
    init {
        require(serverInstanceId.isNotBlank()) { "serverInstanceId must not be blank" }
        require(accountUserId.isNotBlank()) { "accountUserId must not be blank" }
    }
}

enum class DiagnosticsConsentMode { ASK, ALWAYS, NEVER }

data class DiagnosticsConsentRecord(
    val mode: DiagnosticsConsentMode,
    val noticeVersion: Int,
)

@Serializable
data class SentDiagnosticsReport(
    val shortId: String,
    val sentAtEpochMs: Long,
)

fun interface DiagnosticsBindingPurger {
    suspend fun purge(binding: DiagnosticsBinding)
}

class DiagnosticsSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val bindingPurger: DiagnosticsBindingPurger,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    init {
        require(historyLimit > 0) { "historyLimit must be positive" }
    }

    suspend fun consent(
        binding: DiagnosticsBinding,
        currentNoticeVersion: Int,
    ): DiagnosticsConsentRecord {
        require(currentNoticeVersion > 0) { "currentNoticeVersion must be positive" }
        val keys = keys(binding)
        val preferences = dataStore.data.first()
        val storedMode = preferences[keys.consentMode]
            ?.let { raw -> DiagnosticsConsentMode.entries.firstOrNull { it.name == raw } }
            ?: DiagnosticsConsentMode.ASK
        val storedNotice = preferences[keys.noticeVersion] ?: currentNoticeVersion
        if (storedMode == DiagnosticsConsentMode.ALWAYS && storedNotice != currentNoticeVersion) {
            dataStore.edit { mutable ->
                mutable[keys.consentMode] = DiagnosticsConsentMode.ASK.name
                mutable[keys.noticeVersion] = currentNoticeVersion
                mutable[keys.debugLogging] = false
            }
            return DiagnosticsConsentRecord(DiagnosticsConsentMode.ASK, currentNoticeVersion)
        }
        return DiagnosticsConsentRecord(storedMode, storedNotice)
    }

    suspend fun setConsent(
        binding: DiagnosticsBinding,
        mode: DiagnosticsConsentMode,
        noticeVersion: Int,
    ) {
        require(noticeVersion > 0) { "noticeVersion must be positive" }
        val keys = keys(binding)
        dataStore.edit { preferences ->
            preferences[keys.consentMode] = mode.name
            preferences[keys.noticeVersion] = noticeVersion
            if (mode == DiagnosticsConsentMode.NEVER) {
                preferences[keys.debugLogging] = false
                preferences.remove(keys.sentHistory)
            }
        }
        if (mode == DiagnosticsConsentMode.NEVER) bindingPurger.purge(binding)
    }

    suspend fun debugLogging(binding: DiagnosticsBinding): Boolean =
        dataStore.data.first()[keys(binding).debugLogging] ?: false

    suspend fun setDebugLogging(binding: DiagnosticsBinding, enabled: Boolean) {
        val keys = keys(binding)
        dataStore.edit { preferences -> preferences[keys.debugLogging] = enabled }
    }

    suspend fun recordSent(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long) {
        require(shortId.isNotBlank()) { "shortId must not be blank" }
        val keys = keys(binding)
        dataStore.edit { preferences ->
            val existing = decodeHistory(preferences[keys.sentHistory])
            val updated = (listOf(SentDiagnosticsReport(shortId, sentAtEpochMs)) + existing)
                .distinctBy(SentDiagnosticsReport::shortId)
                .sortedByDescending(SentDiagnosticsReport::sentAtEpochMs)
                .take(historyLimit)
            preferences[keys.sentHistory] = JSON.encodeToString(updated)
        }
    }

    suspend fun sentHistory(binding: DiagnosticsBinding): List<SentDiagnosticsReport> =
        decodeHistory(dataStore.data.first()[keys(binding).sentHistory])
            .sortedByDescending(SentDiagnosticsReport::sentAtEpochMs)
            .take(historyLimit)

    suspend fun purgeBinding(binding: DiagnosticsBinding) {
        val prefix = bindingKey(binding)
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { key -> preferences.removeUntyped(key) }
        }
        bindingPurger.purge(binding)
    }

    private fun decodeHistory(raw: String?): List<SentDiagnosticsReport> =
        raw?.let { encoded -> runCatching { JSON.decodeFromString<List<SentDiagnosticsReport>>(encoded) }.getOrNull() }
            .orEmpty()

    private fun keys(binding: DiagnosticsBinding): BindingKeys {
        val prefix = bindingKey(binding)
        return BindingKeys(
            consentMode = stringPreferencesKey("${prefix}consent_mode"),
            noticeVersion = intPreferencesKey("${prefix}notice_version"),
            debugLogging = booleanPreferencesKey("${prefix}debug_logging"),
            sentHistory = stringPreferencesKey("${prefix}sent_history"),
        )
    }

    private fun bindingKey(binding: DiagnosticsBinding): String {
        val input = "${binding.serverInstanceId}\u0000${binding.accountUserId}".encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return buildString(17) {
            append("diagnostics.binding.")
            digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
            append('.')
        }
    }

    private data class BindingKeys(
        val consentMode: Preferences.Key<String>,
        val noticeVersion: Preferences.Key<Int>,
        val debugLogging: Preferences.Key<Boolean>,
        val sentHistory: Preferences.Key<String>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun androidx.datastore.preferences.core.MutablePreferences.removeUntyped(key: Preferences.Key<*>) {
        remove(key as Preferences.Key<Any>)
    }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 20
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
