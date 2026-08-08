package com.bing.androidvoiceflow.capture.entry

import android.content.Intent
import androidx.activity.ComponentActivity

internal fun Intent?.hasTextPlainAction(expectedAction: String): Boolean =
    this?.action == expectedAction && type == "text/plain"

internal fun ComponentActivity.resolveSourcePackage(): String? =
    callingPackage ?: referrer
        ?.takeIf { it.scheme == "android-app" }
        ?.host
