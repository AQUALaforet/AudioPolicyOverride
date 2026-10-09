package dev.aqua.audiopolicy.shizuku

import android.content.ServiceConnection
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Small boundary for testing connection callbacks without a privileged Android server. */
internal interface ShizukuAccess {
    fun ping(): Boolean
    fun version(): Int
    fun compatible(): Boolean
    fun granted(): Boolean
    fun rationale(): Boolean
    fun request(code: Int)
    fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection)
    fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean)
    fun listen(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
               permission: Shizuku.OnRequestPermissionResultListener)
    fun stopListening(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                      permission: Shizuku.OnRequestPermissionResultListener)
}

internal object RealShizukuAccess : ShizukuAccess {
    override fun ping() = Shizuku.pingBinder()
    override fun version() = Shizuku.getVersion()
    override fun compatible() = !Shizuku.isPreV11() && version() >= 12
    override fun granted() = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    override fun rationale() = Shizuku.shouldShowRequestPermissionRationale()
    override fun request(code: Int) = Shizuku.requestPermission(code)
    override fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) = Shizuku.bindUserService(args, connection)
    override fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean) =
        Shizuku.unbindUserService(args, connection, remove)
    override fun listen(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                        permission: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
    }
    override fun stopListening(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                               permission: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
    }
}
