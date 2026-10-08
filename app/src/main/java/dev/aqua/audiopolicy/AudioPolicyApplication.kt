package dev.aqua.audiopolicy

import android.app.Application
import dev.aqua.audiopolicy.automation.AudioPolicyEngine

class AudioPolicyApplication : Application() {
    val engine by lazy { AudioPolicyEngine(this) }
}
