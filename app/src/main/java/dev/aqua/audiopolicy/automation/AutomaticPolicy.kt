package dev.aqua.audiopolicy.automation

import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.data.OverrideController
import dev.aqua.audiopolicy.data.OverrideOwner
import dev.aqua.audiopolicy.data.PolicySnapshot

/** One reconciliation reads reality before deciding and never takes ownership from manual ON. */
class AutomaticPolicy(private val controller: OverrideController) {
    suspend fun reconcile(targetActive: Boolean, canRelease: suspend () -> Boolean = { true }): PolicySnapshot {
        val actual = controller.read()
        check(ForceUse.supported(actual.current)) { "想定外の現在値です。自動切替を停止しています。" }
        check(!actual.record.overrideActive || actual.canRestore) { "復元情報が不正です。自動切替を停止しています。" }
        if (!targetActive) return controller.releaseAutomatic(canRelease)
        check(!actual.record.changePending) { "前回の変更が未確認です。自動切替を停止しています。復元してください。" }
        if (actual.record.overrideActive && actual.current != ForceUse.NONE) {
            error("保存状態と実際の値が一致しません。自動切替を一時停止しました。")
        }
        return if (actual.enabled) actual else controller.enable(OverrideOwner.AUTOMATIC)
    }
    suspend fun setManual(enabled: Boolean, targetActive: Boolean): PolicySnapshot = when {
        enabled -> controller.enable(OverrideOwner.MANUAL)
        targetActive -> controller.enable(OverrideOwner.AUTOMATIC, transferManual = true)
        else -> controller.restore()
    }
}
