package com.baystudio.droide.ui

internal enum class AccessoryModifier { CTRL, ALT, SHIFT }
internal enum class AccessoryModifierMode { OFF, ARMED, LOCKED }

internal data class AccessoryModifierSnapshot(
    val ctrl: AccessoryModifierMode = AccessoryModifierMode.OFF,
    val alt: AccessoryModifierMode = AccessoryModifierMode.OFF,
    val shift: AccessoryModifierMode = AccessoryModifierMode.OFF,
) {
    fun mode(modifier: AccessoryModifier): AccessoryModifierMode = when (modifier) {
        AccessoryModifier.CTRL -> ctrl
        AccessoryModifier.ALT -> alt
        AccessoryModifier.SHIFT -> shift
    }

    fun withMode(modifier: AccessoryModifier, mode: AccessoryModifierMode): AccessoryModifierSnapshot = when (modifier) {
        AccessoryModifier.CTRL -> copy(ctrl = mode)
        AccessoryModifier.ALT -> copy(alt = mode)
        AccessoryModifier.SHIFT -> copy(shift = mode)
    }
}

/**
 * Synchronous modifier latch. The first completed tap arms immediately; a second
 * completed tap within the platform window locks it. No timer or delayed action
 * can arm a modifier after the following character has already been delivered.
 * Consuming a key or clearing focus also ends the double-tap sequence. Only the
 * Compose controller publishes [snapshot]; this model has no platform dependencies.
 */
internal class AccessoryModifierPolicy {
    var snapshot = AccessoryModifierSnapshot()
        private set
    private var lastTapped: AccessoryModifier? = null
    private var lastTapUptimeMillis = 0L

    fun tapAt(modifier: AccessoryModifier, uptimeMillis: Long, minTimeMillis: Long, timeoutMillis: Long) {
        val elapsed = uptimeMillis - lastTapUptimeMillis
        val lock = lastTapped == modifier && snapshot.mode(modifier) == AccessoryModifierMode.ARMED &&
            uptimeMillis >= lastTapUptimeMillis && elapsed >= minTimeMillis.coerceAtLeast(0) &&
            elapsed <= timeoutMillis.coerceAtLeast(0)
        if (lock) lock(modifier) else tap(modifier)
        if (snapshot.mode(modifier) == AccessoryModifierMode.ARMED) {
            lastTapped = modifier
            lastTapUptimeMillis = uptimeMillis
        }
    }

    fun tap(modifier: AccessoryModifier) {
        endTapSequence()
        snapshot = snapshot.withMode(modifier, when (snapshot.mode(modifier)) {
            AccessoryModifierMode.OFF -> AccessoryModifierMode.ARMED
            AccessoryModifierMode.ARMED, AccessoryModifierMode.LOCKED -> AccessoryModifierMode.OFF
        })
    }

    fun lock(modifier: AccessoryModifier) {
        endTapSequence()
        snapshot = snapshot.withMode(modifier, if (snapshot.mode(modifier) == AccessoryModifierMode.LOCKED)
            AccessoryModifierMode.OFF else AccessoryModifierMode.LOCKED)
    }

    fun consumeArmed() {
        endTapSequence()
        fun consumed(mode: AccessoryModifierMode) = if (mode == AccessoryModifierMode.ARMED) AccessoryModifierMode.OFF else mode
        snapshot = AccessoryModifierSnapshot(consumed(snapshot.ctrl), consumed(snapshot.alt), consumed(snapshot.shift))
    }

    fun clear() {
        endTapSequence()
        snapshot = AccessoryModifierSnapshot()
    }

    private fun endTapSequence() { lastTapped = null }
}
