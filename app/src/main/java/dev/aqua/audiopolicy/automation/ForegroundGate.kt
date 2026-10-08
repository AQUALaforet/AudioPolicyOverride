package dev.aqua.audiopolicy.automation

/** Unknown observations never count as leaving an app. Uses a monotonic clock. */
class ForegroundGate(private val exitDelayMs: Long = 800) {
    private var active = false
    private var exitSince: Long? = null
    fun update(matched: Boolean?, now: Long): Boolean {
        when (matched) {
            true -> { active = true; exitSince = null }
            null -> exitSince = null
            false -> if (active) {
                val since = exitSince ?: now.also { exitSince = it }
                if (now - since >= exitDelayMs) { active = false; exitSince = null }
            }
        }
        return active
    }
    fun reset() { active = false; exitSince = null }
}
