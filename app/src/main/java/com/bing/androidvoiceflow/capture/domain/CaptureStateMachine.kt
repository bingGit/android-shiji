package com.bing.androidvoiceflow.capture.domain

internal object CaptureStateMachine {
    fun canTransitionSingleCapture(
        current: SingleCaptureState,
        next: SingleCaptureState
    ): Boolean = next in when (current) {
        SingleCaptureState.LocalGrace -> setOf(
            SingleCaptureState.Frozen,
            SingleCaptureState.Abandoned
        )
        SingleCaptureState.Frozen,
        SingleCaptureState.Abandoned -> emptySet()
    }

    fun canTransitionReadingSession(
        current: ReadingSessionState,
        next: ReadingSessionState
    ): Boolean = next in when (current) {
        ReadingSessionState.Active -> setOf(
            ReadingSessionState.AwaitingFinish,
            ReadingSessionState.Frozen,
            ReadingSessionState.Abandoned
        )
        ReadingSessionState.AwaitingFinish -> setOf(
            ReadingSessionState.Active,
            ReadingSessionState.Frozen,
            ReadingSessionState.Abandoned
        )
        ReadingSessionState.Frozen,
        ReadingSessionState.Abandoned -> emptySet()
    }

    fun canTransitionOutboundRequest(
        current: OutboundRequestState,
        next: OutboundRequestState
    ): Boolean = next in when (current) {
        OutboundRequestState.Pending -> setOf(OutboundRequestState.Sending)
        OutboundRequestState.Sending -> setOf(
            OutboundRequestState.Pending,
            OutboundRequestState.RetryWait,
            OutboundRequestState.AuthRequired,
            OutboundRequestState.Failed
        )
        OutboundRequestState.RetryWait -> setOf(OutboundRequestState.Sending)
        OutboundRequestState.AuthRequired -> setOf(OutboundRequestState.Pending)
        OutboundRequestState.Failed -> emptySet()
    }
}
