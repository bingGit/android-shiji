package com.bing.androidvoiceflow.capture.accessibility

internal class TriplePressDetector(
    private val windowMillis: Long = 900L,
    private val cooldownMillis: Long = 1_500L
) {
    private val pressTimes = ArrayDeque<Long>(3)
    private var lastTriggerAt = Long.MIN_VALUE

    fun registerPress(timestamp: Long): Boolean {
        if (lastTriggerAt != Long.MIN_VALUE && timestamp - lastTriggerAt < cooldownMillis) {
            return false
        }
        while (pressTimes.isNotEmpty() && timestamp - pressTimes.first() > windowMillis) {
            pressTimes.removeFirst()
        }
        pressTimes.addLast(timestamp)
        if (pressTimes.size < 3) return false

        pressTimes.clear()
        lastTriggerAt = timestamp
        return true
    }

    fun reset() {
        pressTimes.clear()
    }
}
