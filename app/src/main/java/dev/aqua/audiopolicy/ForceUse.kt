package dev.aqua.audiopolicy

object ForceUse {
    const val FOR_SYSTEM = 4
    const val NONE = 0
    const val SYSTEM_ENFORCED = 11
    fun supported(value: Int) = value == NONE || value == SYSTEM_ENFORCED
    fun label(value: Int?) = when (value) {
        NONE -> "FORCE_NONE"
        SYSTEM_ENFORCED -> "FORCE_SYSTEM_ENFORCED"
        else -> "UNKNOWN"
    }
}
