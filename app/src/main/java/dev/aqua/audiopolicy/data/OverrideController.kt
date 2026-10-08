package dev.aqua.audiopolicy.data

import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.shizuku.AudioPolicyPort

data class PolicySnapshot(val current: Int, val record: RestoreRecord) {
    val enabled get() = current == ForceUse.NONE && (record.overrideActive || record.changePending)
    val canRestore get() = record.originalForceUse?.let(ForceUse::supported) == true
    val warning: String? get() = when {
        !ForceUse.supported(current) -> "想定外の FOR_SYSTEM 値です。変更を停止しています。"
        record.changePending -> "前回の変更が完了したか確認できません。実際の値を表示しています。保存した元の値への復元が可能です。"
        record.overrideActive && current != ForceUse.NONE -> "保存状態と実際の値が一致しません。実際の値を優先しています。"
        record.overrideActive && !canRestore -> "復元情報が不正です。元の値への復元はできません。"
        else -> null
    }
}

/** Calls are serialized by the application engine. */
class OverrideController(private val port: AudioPolicyPort, private val store: RestoreStore) {
    suspend fun read() = PolicySnapshot(port.getForceUse(), store.read())

    suspend fun enable(owner: OverrideOwner = OverrideOwner.MANUAL): PolicySnapshot {
        val before = read()
        require(ForceUse.supported(before.current)) { "想定外の値 ${before.current} のため変更できません。" }
        // Preserve the first backup on repeated enable and across process restarts.
        val original = if (before.current == ForceUse.NONE &&
            (before.record.overrideActive || before.record.changePending)) {
            before.record.originalForceUse ?: error("復元情報がありません。")
        } else before.current
        require(ForceUse.supported(original)) { "復元情報が不正です。" }
        val pending = RestoreRecord(original, before.record.overrideActive, true, owner)
        store.write(pending) // Never alter the system unless the recovery record is durable.
        val result = port.setForceUse(ForceUse.NONE)
        if (result != 0) {
            store.write(before.record)
            error("AudioSystem.setForceUse() が失敗しました（戻り値 $result）。")
        }
        store.write(RestoreRecord(original, overrideActive = true, owner = owner))
        return read().also {
            check(it.current == ForceUse.NONE) { "書き込みは成功しましたが、実際の値は ${it.current} です。再読み込みしてください。" }
        }
    }

    suspend fun releaseAutomatic(): PolicySnapshot {
        val before = read()
        if (before.record.owner != OverrideOwner.AUTOMATIC || !before.canRestore) return before
        // Do not overwrite a setting another app/OS has already restored or changed.
        if (before.current == before.record.originalForceUse) {
            store.write(RestoreRecord())
            return PolicySnapshot(before.current, RestoreRecord())
        }
        check(before.current == ForceUse.NONE) { "実際の音声設定が外部で変更されています。自動復元を停止し、復元情報を保持しました。" }
        return restore()
    }

    suspend fun restore(): PolicySnapshot {
        val before = read()
        require(ForceUse.supported(before.current)) { "想定外の値 ${before.current} のため復元を停止しました。" }
        val original = before.record.originalForceUse ?: error("保存された変更前の値がありません。")
        require(ForceUse.supported(original)) { "安全に復元できる値は 0 または 11 のみです。" }
        store.write(before.record.copy(changePending = true))
        val result = port.setForceUse(original)
        if (result != 0) {
            store.write(before.record)
            error("復元が失敗しました（戻り値 $result）。")
        }
        // Clear only after reading back the restored value. Keep backup on verification failure.
        val actual = port.getForceUse()
        check(actual == original) { "復元は成功を返しましたが、実際の値は $actual です。復元情報は保持しています。" }
        store.write(RestoreRecord())
        return PolicySnapshot(actual, RestoreRecord())
    }
}
