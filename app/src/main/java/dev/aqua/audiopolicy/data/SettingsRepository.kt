package dev.aqua.audiopolicy.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("audio_policy_restore")

enum class OverrideOwner { MANUAL, AUTOMATIC }
data class AutomationSettings(val enabled: Boolean = false, val packages: Set<String> = emptySet())

data class RestoreRecord(
    val originalForceUse: Int? = null,
    val overrideActive: Boolean = false,
    val changePending: Boolean = false,
    val owner: OverrideOwner = OverrideOwner.MANUAL
)

interface RestoreStore {
    suspend fun read(): RestoreRecord
    suspend fun write(record: RestoreRecord)
}

class SettingsRepository(context: Context) : RestoreStore {
    private val store = context.applicationContext.settingsStore
    val automation = store.data.map { AutomationSettings(it[AUTO_ENABLED] ?: false, it[TARGETS] ?: emptySet()) }
    suspend fun setAutomationEnabled(enabled: Boolean) { store.edit { it[AUTO_ENABLED] = enabled } }
    suspend fun setTargets(packages: Set<String>) { store.edit { it[TARGETS] = packages.toSet() } }
    override suspend fun read(): RestoreRecord = store.data.first().let {
        RestoreRecord(it[ORIGINAL], it[ACTIVE] ?: false, it[PENDING] ?: false,
            if (it[OWNER] == OverrideOwner.AUTOMATIC.name) OverrideOwner.AUTOMATIC else OverrideOwner.MANUAL)
    }
    override suspend fun write(record: RestoreRecord) {
        store.edit {
            record.originalForceUse?.let { value -> it[ORIGINAL] = value } ?: it.remove(ORIGINAL)
            it[ACTIVE] = record.overrideActive
            it[PENDING] = record.changePending
            it[OWNER] = record.owner.name
        }
    }
    private companion object {
        val ORIGINAL = intPreferencesKey("originalForceUse")
        val ACTIVE = booleanPreferencesKey("overrideActive")
        val PENDING = booleanPreferencesKey("changePending")
        val OWNER = stringPreferencesKey("overrideOwner")
        val AUTO_ENABLED = booleanPreferencesKey("automationEnabled")
        val TARGETS = stringSetPreferencesKey("targetPackages")
    }
}
