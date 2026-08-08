package com.bing.androidvoiceflow.capture.data

import androidx.room.TypeConverter
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState

internal class CaptureTypeConverters {
    @TypeConverter
    fun captureTypeToStorage(value: CaptureType): String = value.storageValue

    @TypeConverter
    fun captureTypeFromStorage(value: String): CaptureType = CaptureType.fromStorage(value)

    @TypeConverter
    fun singleCaptureStateToStorage(value: SingleCaptureState): String = value.storageValue

    @TypeConverter
    fun singleCaptureStateFromStorage(value: String): SingleCaptureState =
        SingleCaptureState.fromStorage(value)

    @TypeConverter
    fun readingSessionStateToStorage(value: ReadingSessionState): String = value.storageValue

    @TypeConverter
    fun readingSessionStateFromStorage(value: String): ReadingSessionState =
        ReadingSessionState.fromStorage(value)

    @TypeConverter
    fun readingBlockTypeToStorage(value: ReadingBlockType): String = value.storageValue

    @TypeConverter
    fun readingBlockTypeFromStorage(value: String): ReadingBlockType =
        ReadingBlockType.fromStorage(value)

    @TypeConverter
    fun outboundRequestStateToStorage(value: OutboundRequestState): String = value.storageValue

    @TypeConverter
    fun outboundRequestStateFromStorage(value: String): OutboundRequestState =
        OutboundRequestState.fromStorage(value)

    @TypeConverter
    fun captureOriginTypeToStorage(value: CaptureOriginType): String = value.storageValue

    @TypeConverter
    fun captureOriginTypeFromStorage(value: String): CaptureOriginType =
        CaptureOriginType.fromStorage(value)
}
