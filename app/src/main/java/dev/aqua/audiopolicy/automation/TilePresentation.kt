package dev.aqua.audiopolicy.automation
import dev.aqua.audiopolicy.ForceUse
internal fun tileCanOperate(state: PolicyUiState): Boolean {
    val snapshot = state.snapshot ?: return false
    return state.settingsReady && state.connection.connected && state.connection.ready &&
        state.connection.granted && state.connection.running && !state.busy &&
        ForceUse.supported(snapshot.current) && snapshot.warning == null &&
        (!snapshot.record.hasRecovery || snapshot.enabled)
}
internal fun tileLabel(state: PolicyUiState): String = when {
    state.busy -> "操作中／復元待ち"
    state.recoveryRecord?.changePending == true || state.recoveryRecord?.restoreRequested == true -> "復元待ち・アプリで確認"
    !tileCanOperate(state) -> "Shizuku 未接続／状態未確認"
    state.manualEnabled -> "手動 Override 有効"
    state.automaticActive -> "自動 Override 有効"
    else -> "Override 無効"
}
