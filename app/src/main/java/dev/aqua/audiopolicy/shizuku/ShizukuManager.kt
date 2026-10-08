package dev.aqua.audiopolicy.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.aqua.audiopolicy.BuildConfig
import dev.aqua.audiopolicy.aidl.IAudioPolicyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ConnectionState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val granted: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val session: Long = 0,
    val error: String? = null
)

interface AudioPolicyPort {
    suspend fun getForceUse(): Int
    suspend fun setForceUse(config: Int): Int
}

/** Application-scoped; never holds an Activity. Lifecycle callbacks run on main. */
class ShizukuManager(context: Context) : AudioPolicyPort {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    @Volatile private var service: IAudioPolicyService? = null
    private var closed = false
    private var bound = false
    private var linkedBinder: IBinder? = null
    private var recipient: IBinder.DeathRecipient? = null
    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, AudioPolicyUserService::class.java.name)
    ).processNameSuffix("audio_policy").daemon(false).version(2)
    private val timeout = Runnable {
        if (mutableState.value.connecting) {
            detach()
            mutableState.value = mutableState.value.copy(connecting = false, connected = false,
                error = "UserService 接続がタイムアウトしました。再読み込みしてください。")
        }
    }
    private var activeConnection: ServiceConnection? = null
    private fun newConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || activeConnection !== this) return
            try {
                clearService()
                val death = IBinder.DeathRecipient {
                    handler.post { if (linkedBinder === binder) disconnected("UserService Binder が終了しました。") }
                }
                binder.linkToDeath(death, 0)
                linkedBinder = binder
                recipient = death
                service = IAudioPolicyService.Stub.asInterface(binder)
                handler.removeCallbacks(timeout)
                Log.i(TAG, "UserService connected")
                mutableState.value = mutableState.value.copy(connecting = false, connected = true,
                    session = mutableState.value.session + 1, error = null)
            } catch (e: Exception) { fail(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (activeConnection === this) disconnected("UserService が切断されました。")
        }
        override fun onBindingDied(name: ComponentName) {
            if (activeConnection === this) disconnected("UserService 接続が終了しました。")
        }
        override fun onNullBinding(name: ComponentName) {
            if (activeConnection === this) disconnected("UserService の Binder が取得できません。")
        }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku Binder received")
        refresh()
    }
    private val dead = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku Binder died")
        disconnected("Shizuku が停止しました。起動後、再読み込みしてください。")
        mutableState.value = mutableState.value.copy(running = false, granted = false)
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            refresh()
            if (result != PackageManager.PERMISSION_GRANTED) {
                mutableState.value = mutableState.value.copy(error = "Shizuku 権限が許可されませんでした。")
            }
        }
    }

    init {
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
        refresh()
    }

    fun refresh() {
        if (closed) return
        try {
            val installed = try {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            val running = Shizuku.pingBinder()
            val compatible = running && !Shizuku.isPreV11() && Shizuku.getVersion() >= 12
            val granted = compatible && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku status: installed=$installed, running=$running, granted=$granted")
            mutableState.value = mutableState.value.copy(installed = installed, running = running, granted = granted,
                error = if (running && !compatible) "Shizuku v12 以降が必要です。" else null)
            if (!granted) detach() else if (!bound) bind()
        } catch (e: Exception) { fail(e) }
    }

    fun requestPermission() {
        try {
            check(Shizuku.pingBinder()) { "Shizuku を先に起動してください。" }
            check(!Shizuku.isPreV11() && Shizuku.getVersion() >= 12) { "Shizuku v12 以降が必要です。" }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) refresh()
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                mutableState.value = mutableState.value.copy(error = "Shizuku アプリの管理画面で、このアプリへの権限を許可してください。")
            } else Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) { fail(e) }
    }

    private fun bind() {
        bound = true
        val connection = newConnection()
        activeConnection = connection
        mutableState.value = mutableState.value.copy(connecting = true, connected = false)
        handler.postDelayed(timeout, 15_000)
        try {
            Log.i(TAG, "Binding UserService")
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) { fail(e) }
    }

    private fun disconnected(message: String) {
        if (closed) return
        detach()
        mutableState.value = mutableState.value.copy(connecting = false, connected = false, error = message)
        Log.w(TAG, message)
    }
    private fun clearService() {
        service = null
        recipient?.let { death -> runCatching { linkedBinder?.unlinkToDeath(death, 0) } }
        linkedBinder = null
        recipient = null
    }
    private fun detach() {
        handler.removeCallbacks(timeout)
        clearService()
        val wasBound = bound
        val connection = activeConnection
        activeConnection = null // Ignore delayed disconnect callbacks from an old binding.
        bound = false
        if (wasBound && connection != null && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(args, connection, true) }
            catch (e: Exception) { Log.w(TAG, "UserService unbind failed", e) }
        }
        mutableState.value = mutableState.value.copy(connecting = false, connected = false)
    }
    private fun fail(e: Exception) {
        Log.e(TAG, "Shizuku/Binder exception", e)
        disconnected(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun <T> call(block: (IAudioPolicyService) -> T): T = withContext(Dispatchers.IO) {
        val remote = service
        try {
            check(Shizuku.pingBinder()) { "Shizuku が停止しています。" }
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku 権限がありません。" }
            checkNotNull(remote) { "UserService 未接続です。" }
            check(remote.asBinder().isBinderAlive) { "UserService Binder が終了しています。" }
            block(remote)
        } catch (e: Exception) {
            Log.e(TAG, "Binder call failed", e)
            if (e is android.os.DeadObjectException || e is SecurityException) {
                handler.post { if (service === remote) fail(e) }
            }
            throw e
        }
    }
    override suspend fun getForceUse(): Int = call { it.getForceUse() }
    override suspend fun setForceUse(config: Int): Int = call { it.setForceUse(config) }
    suspend fun getForegroundPackage(): String = call { it.getForegroundPackage().orEmpty() }

    fun close() {
        closed = true
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        detach()
    }
    companion object { private const val TAG = "ShizukuManager"; private const val REQUEST_CODE = 100 }
}
