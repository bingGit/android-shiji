package com.bing.androidvoiceflow.provider

import com.bing.androidvoiceflow.core.ProviderConfig
import com.bing.androidvoiceflow.core.RealtimeProviderProtocol
import com.bing.androidvoiceflow.core.RealtimeTranscriptionProvider

class RealtimeProviderFactory {
    fun create(config: ProviderConfig): RealtimeTranscriptionProvider {
        return when (config.realtimeProtocol) {
            RealtimeProviderProtocol.OpenAiRealtime -> OpenAiRealtimeTranscriptionProvider()
            RealtimeProviderProtocol.AliyunParaformer -> AliyunParaformerRealtimeProvider()
        }
    }
}
