package com.bing.androidvoiceflow.capture.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriplePressDetectorTest {
    @Test
    fun `triggers on three presses inside window`() {
        val detector = TriplePressDetector()

        assertFalse(detector.registerPress(100L))
        assertFalse(detector.registerPress(400L))
        assertTrue(detector.registerPress(800L))
    }

    @Test
    fun `does not combine presses outside window`() {
        val detector = TriplePressDetector()

        assertFalse(detector.registerPress(100L))
        assertFalse(detector.registerPress(500L))
        assertFalse(detector.registerPress(1_100L))
    }

    @Test
    fun `reset cancels partial gesture`() {
        val detector = TriplePressDetector()

        assertFalse(detector.registerPress(100L))
        assertFalse(detector.registerPress(300L))
        detector.reset()
        assertFalse(detector.registerPress(500L))
    }

    @Test
    fun `cooldown prevents immediate retrigger`() {
        val detector = TriplePressDetector()

        detector.registerPress(100L)
        detector.registerPress(300L)
        assertTrue(detector.registerPress(500L))
        assertFalse(detector.registerPress(700L))
        assertFalse(detector.registerPress(900L))
        assertFalse(detector.registerPress(1_100L))
    }
}
