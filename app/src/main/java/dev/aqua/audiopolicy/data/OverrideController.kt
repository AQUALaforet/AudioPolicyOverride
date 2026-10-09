package dev.aqua.audiopolicy.data

import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.diagnostics.DiagnosticEvent
import dev.aqua.audiopolicy.shizuku.AudioPolicyPort
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PolicySnapshot(val current: Int, val record: RestoreRecord) {
    val enabled get() = current == ForceUse.NONE && record.overrideActive && !record.changePending && !record.restoreRequested && canRestore
    val canRestore get() = record.originalForceUse?.let(ForceUse::supported) == true
    val warning: String? get() = when {
        !ForceUse.supported(current) -> "想定外の FOR_SYSTEM 値です。変更を停止しています。"
        record.restoreRequested -> "復元要求が未完了です。復元情報を保持しています。"
        record.changePending -> "前回の変更が完了したか確認できません。実際の値を表示しています。保存した元の値への復元が可能です。"
        record.overrideActive && current != ForceUse.NONE -> "保存状態と実際の値が一致しません。実際の値を優先しています。"
        record.hasRecovery && !canRestore -> "復元情報が不正です。元の値への復元はできません。"
        else -> null
    }
}

/** Owns the recovery transaction lock, including read-back and durable commit. */
class OverrideController(private val port: AudioPolicyPort, private val store: RestoreStore,
    private val diagnostic: suspend (DiagnosticEvent) -> Unit = {}) {
    private val mutex = Mutex()
    private suspend fun readActual() = PolicySnapshot(port.getForceUse(), store.read())
    suspend fun read() = mutex.withLock { readActual() }
    // Once a write transaction begins, cancellation must not release its lock before the
    // synchronous Binder call and recovery commit finish. Process death still leaves pending.
    private suspend fun <T> transaction(block: suspend () -> T): T = mutex.withLock {
        withContext(NonCancellable) { block() }
    }

    private suspend fun event(event: DiagnosticEvent) {
        try { diagnostic(event) } catch (_: Exception) { /* diagnostics must never block recovery */ }
    }
    private suspend fun writeNative(config: Int, action: String): Int = try {
        port.setForceUse(config).also { event(DiagnosticEvent(action, "", config, result = it)) }
    } catch (e: Exception) {
        event(DiagnosticEvent(action + "_FAILED", "", config, failure = e.javaClass.simpleName)); throw e
    }
    private suspend fun readBack(config: Int, result: Int): Int = try {
        port.getForceUse().also { event(DiagnosticEvent("READ_BACK", "", config, result, it)) }
    } catch (e: Exception) {
        event(DiagnosticEvent("READ_BACK_FAILED", "", config, result, failure = e.javaClass.simpleName)); throw e
    }

    suspend fun enable(owner: OverrideOwner = OverrideOwner.MANUAL, transferManual: Boolean = false): PolicySnapshot = transaction {
        val before = readActual()
        require(ForceUse.supported(before.current)) { "想定外の値 ${before.current} のため変更できません。" }
        check(!before.record.changePending) { "前回の変更が未確認です。先に復元してください。" }
        check(!before.record.restoreRequested) { "復元待ちです。先に復元してください。" }
        if (before.record.hasRecovery) {
            check(before.canRestore && before.record.overrideActive && before.current == ForceUse.NONE) {
                "保存状態と実際の値が一致しません。復元情報を保持しています。先に復元してください。"
            }
            // Automatic checks may race a manual command; never steal manual ownership.
            if (owner == OverrideOwner.AUTOMATIC && before.record.owner == OverrideOwner.MANUAL && !transferManual) {
                return@transaction before
            }
            val record = before.record.copy(owner = owner)
            if (record != before.record) store.write(record)
            return@transaction PolicySnapshot(before.current, record)
        }
        val original = before.current
        require(ForceUse.supported(original)) { "復元情報が不正です。" }
        val pending = RestoreRecord(original, before.record.overrideActive, true, owner)
        store.write(pending) // Never alter the system unless the recovery record is durable.
        val result = writeNative(ForceUse.NONE, "APPLY")
        if (result != 0) {
            // A failure code is not proof that reality stayed unchanged. Retain pending
            // if the value changed or if even this verification loses its Binder reply.
            if (readBack(ForceUse.NONE, result) == before.current) store.write(before.record)
            error("AudioSystem.setForceUse() が失敗しました（戻り値 $result）。")
        }
        val actual = readBack(ForceUse.NONE, result)
        check(actual == ForceUse.NONE) { "書き込みは成功しましたが、実際の値は $actual です。復元情報を保持しています。" }
        val committed = RestoreRecord(original, overrideActive = true, owner = owner)
        store.write(committed)
        PolicySnapshot(actual, committed)
    }

    suspend fun releaseAutomatic(canRelease: suspend () -> Boolean = { true }): PolicySnapshot = transaction {
        val before = readActual()
        if (before.record.owner != OverrideOwner.AUTOMATIC || !before.record.hasRecovery) return@transaction before
        check(before.canRestore) { "復元情報が不正です。自動復元を停止しています。" }
        check(before.record.overrideActive || before.record.changePending) { "所有権を確認できない復元記録です。自動復元を停止しています。" }
        // Final foreground confirmation happens inside the same transaction as restoration.
        if (!canRelease()) return@transaction before
        // Do not overwrite a setting another app/OS has already restored or changed.
        if (before.current == before.record.originalForceUse) {
            store.write(RestoreRecord())
            return@transaction PolicySnapshot(before.current, RestoreRecord())
        }
        check(before.current == ForceUse.NONE) { "実際の音声設定が外部で変更されています。自動復元を停止し、復元情報を保持しました。" }
        restoreActual(before)
    }

    suspend fun restore(): PolicySnapshot = transaction { restoreActual(readActual()) }
    suspend fun requestRestore() = transaction {
        val record = store.read()
        if (record.hasRecovery) store.write(record.copy(restoreRequested = true))
    }
    suspend fun restoreIfPresent(): PolicySnapshot = transaction {
        val before = readActual()
        if (before.record.hasRecovery) restoreActual(before) else before
    }
    private suspend fun restoreActual(before: PolicySnapshot): PolicySnapshot {
        require(ForceUse.supported(before.current)) { "想定外の値 ${before.current} のため復元を停止しました。" }
        val original = before.record.originalForceUse ?: error("保存された変更前の値がありません。")
        require(ForceUse.supported(original)) { "安全に復元できる値は 0 または 11 のみです。" }
        store.write(before.record.copy(changePending = true))
        val result = writeNative(original, "RESTORE")
        if (result != 0) {
            if (readBack(original, result) == before.current) store.write(before.record)
            error("復元が失敗しました（戻り値 $result）。")
        }
        // Clear only after reading back the restored value. Keep backup on verification failure.
        val actual = readBack(original, result)
        check(actual == original) { "復元は成功を返しましたが、実際の値は $actual です。復元情報は保持しています。" }
        store.write(RestoreRecord())
        return PolicySnapshot(actual, RestoreRecord())
    }
}
