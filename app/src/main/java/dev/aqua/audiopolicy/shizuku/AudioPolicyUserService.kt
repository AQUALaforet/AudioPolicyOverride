package dev.aqua.audiopolicy.shizuku

import android.util.Log
import android.app.ActivityManager
import android.os.Build
import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.aidl.IAudioPolicyService
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** Only this class, running under Shizuku's shell/root identity, accesses AudioSystem. */
class AudioPolicyUserService : IAudioPolicyService.Stub() {
    private val taskReader by lazy {
        if (Build.VERSION.SDK_INT >= 29) {
            val clazz = Class.forName("android.app.ActivityTaskManager")
            Pair(clazz.getDeclaredMethod("getInstance").invoke(null),
                clazz.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType))
        } else {
            val remote = ActivityManager::class.java.getDeclaredMethod("getService").invoke(null)
            val api = Class.forName("android.app.IActivityManager")
            val method = try {
                api.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                // Android 8.x has an additional flags argument; Android 9 uses one argument.
                api.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }
            Pair(remote, method)
        }
    }
    private val methods by lazy {
        val clazz = Class.forName("android.media.AudioSystem")
        Pair(
            clazz.getDeclaredMethod("getForceUse", Int::class.javaPrimitiveType),
            clazz.getDeclaredMethod("setForceUse", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        )
    }

    override fun getForceUse(): Int = reflect {
        (methods.first.invoke(null, ForceUse.FOR_SYSTEM) as Int).also {
            Log.i(TAG, "getForceUse(4)=$it")
        }
    }

    override fun setForceUse(config: Int): Int {
        validateConfig(config)
        return reflect {
            Log.i(TAG, "setForceUse(4, $config)")
            (methods.second.invoke(null, ForceUse.FOR_SYSTEM, config) as Int).also {
                Log.i(TAG, "setForceUse return=$it")
            }
        }
    }

    override fun getForegroundPackage(): String = reflect {
        val (receiver, method) = taskReader
        val tasks = (if (method.parameterTypes.size == 2) method.invoke(receiver, 1, 0)
            else method.invoke(receiver, 1)) as List<*>
        (tasks.firstOrNull() as? ActivityManager.RunningTaskInfo)?.topActivity?.packageName ?: ""
    }

    private fun <T> reflect(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        val cause = if (e is InvocationTargetException) e.targetException else e
        Log.e(TAG, "UserService reflection failed", cause)
        // IllegalStateException is supported by Parcel.readException across Binder.
        throw IllegalStateException("UserService reflection failed: ${cause.javaClass.simpleName}: ${cause.message}")
    } catch (e: LinkageError) {
        Log.e(TAG, "AudioSystem linkage failed", e)
        throw IllegalStateException("AudioSystem unavailable: ${e.javaClass.simpleName}")
    }

    override fun destroy() {
        Log.i(TAG, "UserService destroy; persistent restore record remains in app")
        exitProcess(0)
    }

    companion object {
        private const val TAG = "AudioPolicyUserService"
        internal fun validateConfig(config: Int) {
            require(ForceUse.supported(config)) {
                "Only FORCE_NONE and FORCE_SYSTEM_ENFORCED are allowed"
            }
        }
    }
}
