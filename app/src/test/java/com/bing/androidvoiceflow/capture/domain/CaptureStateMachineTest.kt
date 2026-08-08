package com.bing.androidvoiceflow.capture.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateMachineTest {
    @Test
    fun `single capture only freezes or abandons from grace`() {
        assertTrue(
            CaptureStateMachine.canTransitionSingleCapture(
                SingleCaptureState.LocalGrace,
                SingleCaptureState.Frozen
            )
        )
        assertTrue(
            CaptureStateMachine.canTransitionSingleCapture(
                SingleCaptureState.LocalGrace,
                SingleCaptureState.Abandoned
            )
        )
        assertFalse(
            CaptureStateMachine.canTransitionSingleCapture(
                SingleCaptureState.Frozen,
                SingleCaptureState.LocalGrace
            )
        )
    }

    @Test
    fun `reading session can resume awaiting state but cannot reopen frozen state`() {
        assertTrue(
            CaptureStateMachine.canTransitionReadingSession(
                ReadingSessionState.Active,
                ReadingSessionState.AwaitingFinish
            )
        )
        assertTrue(
            CaptureStateMachine.canTransitionReadingSession(
                ReadingSessionState.AwaitingFinish,
                ReadingSessionState.Active
            )
        )
        assertFalse(
            CaptureStateMachine.canTransitionReadingSession(
                ReadingSessionState.Frozen,
                ReadingSessionState.Active
            )
        )
    }

    @Test
    fun `outbound request requires sending claim before failure state`() {
        assertTrue(
            CaptureStateMachine.canTransitionOutboundRequest(
                OutboundRequestState.Pending,
                OutboundRequestState.Sending
            )
        )
        assertTrue(
            CaptureStateMachine.canTransitionOutboundRequest(
                OutboundRequestState.Sending,
                OutboundRequestState.RetryWait
            )
        )
        assertFalse(
            CaptureStateMachine.canTransitionOutboundRequest(
                OutboundRequestState.Pending,
                OutboundRequestState.Failed
            )
        )
    }
}
