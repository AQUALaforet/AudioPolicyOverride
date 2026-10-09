package dev.aqua.audiopolicy.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("audio_policy_restore")

enum class OverrideOwner { MANUAL, AUTOMATIC }
data class AutomationSettings(val enabled: Boolean = false, val packages: Set<String> = emptySet())

data class RestoreRecord(
    val originalForceUse: Int? = null,
    val overrideActive: Boolean = false,
    val changePending: Boolean = false,
    val owner: OverrideOwner = OverrideOwner.MANUAL,
    val restoreRequested: Boolean = false
) {
    val hasRecovery get() = originalForceUse != null || overrideActive || changePending || restoreRequested
}

interface RestoreStore {
    suspend fun read(): RestoreRecord
    suspend fun write(record: RestoreRecord)
}

interface PolicySettingsStore : RestoreStore {
    val automation: Flow<AutomationSettings>
    suspend fun setAutomationEnabled(enabled: Boolean)
    suspend fun setTargets(packages: Set<String>)
}

class SettingsRepository(context: Context) : PolicySettingsStore {
    private val store = context.applicationContext.settingsStore
    override val automation = store.data.map { AutomationSettings(it[AUTO_ENABLED] ?: false, it[TARGETS] ?: emptySet()) }
    override suspend fun setAutomationEnabled(enabled: Boolean) { store.edit { it[AUTO_ENABLED] = enabled } }
    override suspend fun setTargets(packages: Set<String>) { store.edit { it[TARGETS] = packages.toSet() } }
    override suspend fun read(): RestoreRecord = store.data.first().let {
        RestoreRecord(it[ORIGINAL], it[ACTIVE] ?: false, it[PENDING] ?: false,
            if (it[OWNER] == OverrideOwner.AUTOMATIC.name) OverrideOwner.AUTOMATIC else OverrideOwner.MANUAL,
            it[RESTORE_REQUESTED] ?: false)
    }
    override suspend fun write(record: RestoreRecord) {
        store.edit {
            record.originalForceUse?.let { value -> it[ORIGINAL] = value } ?: it.remove(ORIGINAL)
            it[ACTIVE] = record.overrideActive
            it[PENDING] = record.changePending
            it[OWNER] = record.owner.name
            it[RESTORE_REQUESTED] = record.restoreRequested
        }
    }
    private companion object {
        val ORIGINAL = intPreferencesKey("originalForceUse")
        val ACTIVE = booleanPreferencesKey("overrideActive")
        val PENDING = booleanPreferencesKey("changePending")
        val OWNER = stringPreferencesKey("overrideOwner")
        val RESTORE_REQUESTED = booleanPreferencesKey("restoreRequested")
        val AUTO_ENABLED = booleanPreferencesKey("automationEnabled")
        val TARGETS = stringSetPreferencesKey("targetPackages")
    }
}
