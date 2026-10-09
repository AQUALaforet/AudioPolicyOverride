package dev.aqua.audiopolicy.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.aqua.audiopolicy.BuildConfig
import dev.aqua.audiopolicy.aidl.IAudioPolicyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ConnectionState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val ready: Boolean = false,
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
class ShizukuManager internal constructor(context: Context, private val access: ShizukuAccess,
    private val callDispatcher: CoroutineDispatcher = Dispatchers.IO) : AudioPolicyPort {
    constructor(context: Context) : this(context, RealShizukuAccess)
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    @Volatile private var service: IAudioPolicyService? = null
    private var closed = false
    private var ready = false
    private val retry = Runnable { refresh() }
    private fun retryLater() {
        handler.removeCallbacks(retry)
        if (!closed) handler.postDelayed(retry, 5_000)
    }
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
            retryLater()
        }
    }
    private var activeConnection: ServiceConnection? = null
    private fun newConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || activeConnection !== this) return
            if (linkedBinder === binder && service != null) return
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
                handler.removeCallbacks(retry)
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
        if (closed) return@OnBinderReceivedListener
        ready = true
        detach() // A replacement Shizuku Binder invalidates the old UserService session.
        refresh()
    }
    private val dead = Shizuku.OnBinderDeadListener {
        if (closed) return@OnBinderDeadListener
        ready = false
        disconnected("Shizuku が停止しました。起動後、再読み込みしてください。")
        mutableState.value = mutableState.value.copy(running = false, ready = false, granted = false)
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
        access.listen(received, dead, permission)
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
            val running = access.ping()
            val compatible = running && ready && access.compatible()
            val granted = compatible && access.granted()
            mutableState.value = mutableState.value.copy(installed = installed, running = running, ready = running && ready, granted = granted,
                error = if (running && ready && !compatible) "Shizuku v12 以降が必要です。" else null)
            if (!granted) detach() else if (!bound) bind()
            if (!running || !ready) retryLater()
        } catch (e: Exception) { fail(e) }
    }

    fun requestPermission() {
        try {
            check(access.ping()) { "Shizuku を先に起動してください。" }
            check(ready) { "Shizuku の初期化を待っています。" }
            check(access.compatible()) { "Shizuku v12 以降が必要です。" }
            if (access.granted()) refresh()
            else if (access.rationale()) {
                mutableState.value = mutableState.value.copy(error = "Shizuku アプリの管理画面で、このアプリへの権限を許可してください。")
            } else access.request(REQUEST_CODE)
        } catch (e: Exception) { fail(e) }
    }

    private fun bind() {
        bound = true
        val connection = newConnection()
        activeConnection = connection
        mutableState.value = mutableState.value.copy(connecting = true, connected = false)
        handler.postDelayed(timeout, 15_000)
        try {
            access.bind(args, connection)
        } catch (e: Exception) { fail(e) }
    }

    private fun disconnected(message: String) {
        if (closed) return
        detach()
        mutableState.value = mutableState.value.copy(connecting = false, connected = false, error = message)
        retryLater()
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
        if (wasBound && connection != null) {
            try { if (access.ping()) access.unbind(args, connection, true) }
            catch (e: Exception) { /* policy state handles the error; diagnostic logs are opt-in */ }
            // remove=true does not clear Shizuku-API's local connection list.
            try { access.unbind(args, connection, false) }
            catch (e: Exception) { /* policy state handles the error; diagnostic logs are opt-in */ }
        }
        mutableState.value = mutableState.value.copy(connecting = false, connected = false)
    }
    private fun fail(e: Exception) {
        disconnected(e.message ?: e.javaClass.simpleName)
        val running = runCatching { access.ping() }.getOrDefault(false)
        val granted = e !is SecurityException && running && ready && runCatching { access.granted() }.getOrDefault(false)
        mutableState.value = mutableState.value.copy(running = running, ready = running && ready, granted = granted)
    }

    private suspend fun <T> call(block: (IAudioPolicyService) -> T): T = withContext(callDispatcher) {
        val remote = service
        try {
            check(access.ping()) { "Shizuku が停止しています。" }
            if (!access.granted()) throw SecurityException("Shizuku 権限がありません。")
            checkNotNull(remote) { "UserService 未接続です。" }
            check(remote.asBinder().isBinderAlive) { "UserService Binder が終了しています。" }
            block(remote)
        } catch (e: Exception) {
            if (generateSequence(e as Throwable) { it.cause }.any { it is android.os.RemoteException } || e is SecurityException ||
                !runCatching { access.ping() }.getOrDefault(false) || remote?.asBinder()?.isBinderAlive != true) {
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
        handler.removeCallbacks(retry)
        access.stopListening(received, dead, permission)
        detach()
    }
    companion object { private const val TAG = "ShizukuManager"; private const val REQUEST_CODE = 100 }
}
