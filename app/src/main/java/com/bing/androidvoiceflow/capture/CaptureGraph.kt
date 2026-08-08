package com.bing.androidvoiceflow.capture

import android.content.Context
import com.bing.androidvoiceflow.capture.data.CaptureDatabase
import com.bing.androidvoiceflow.capture.data.CaptureRepository
import com.bing.androidvoiceflow.capture.domain.CapturePayloadFactory
import com.bing.androidvoiceflow.capture.network.CaptureServiceConfigStore
import com.bing.androidvoiceflow.capture.network.CaptureSubmissionRunner
import com.bing.androidvoiceflow.capture.network.OkHttpCaptureApi

internal object CaptureGraph {
    fun database(context: Context): CaptureDatabase = CaptureDatabase.getInstance(context)

    fun repository(context: Context): CaptureRepository = CaptureRepository(
        database = database(context),
        payloadFactory = CapturePayloadFactory(appVersion = context.appVersionName())
    )

    fun configStore(context: Context): CaptureServiceConfigStore =
        CaptureServiceConfigStore(context)

    fun submissionRunner(context: Context): CaptureSubmissionRunner = CaptureSubmissionRunner(
        repository = repository(context),
        api = captureApi(context)
    )

    fun captureApi(context: Context): OkHttpCaptureApi =
        OkHttpCaptureApi(configStore(context))

    private fun Context.appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }
}
