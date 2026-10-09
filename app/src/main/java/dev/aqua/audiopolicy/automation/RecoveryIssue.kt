package dev.aqua.audiopolicy.automation

import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.data.RestoreRecord

enum class RecoveryKind { INVALID_RECORD, RECONNECT_REQUIRED, RESTORE_FAILED, UNCONFIRMED }
data class RecoveryIssue(val kind: RecoveryKind, val title: String, val detail: String)

internal fun recoveryIssue(record: RestoreRecord?, connected: Boolean, failure: String?): RecoveryIssue? {
    if (record == null || !record.hasRecovery) return null
    if (record.originalForceUse?.let(ForceUse::supported) != true) {
        return RecoveryIssue(RecoveryKind.INVALID_RECORD, "復元情報が不正です", "元の値を推測せず保持しています。アプリで確認してください。")
    }
    if (!connected && (record.restoreRequested || failure != null)) {
        return RecoveryIssue(RecoveryKind.RECONNECT_REQUIRED, "復元には再接続が必要です", "Shizuku と UserService に接続できません。復元情報を保持しています。")
    }
    if (failure != null) return RecoveryIssue(RecoveryKind.RESTORE_FAILED, "復元に失敗しました", failure)
    if (record.changePending) return RecoveryIssue(RecoveryKind.UNCONFIRMED, "前回の変更が未確認です", "完了を確認できていません。アプリで実値と復元情報を確認してください。")
    if (record.restoreRequested) return RecoveryIssue(RecoveryKind.UNCONFIRMED, "復元待ちです", "保存した元の値への復元を確認できていません。")
    return null // A normal manual ON, even disconnected, is not a restoration failure.
}
