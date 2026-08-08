package com.bing.androidvoiceflow.capture.entry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bing.androidvoiceflow.capture.ui.ManualCaptureActivity

internal class QuickRecordShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, ManualCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
