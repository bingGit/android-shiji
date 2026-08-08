package com.bing.androidvoiceflow.capture.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualCaptureDraftStoreTest {
    private lateinit var store: ManualCaptureDraftStore

    @Before
    fun setUp() {
        store = ManualCaptureDraftStore(ApplicationProvider.getApplicationContext<Context>())
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun draftSurvivesStoreRecreationWithoutChangingText() {
        val content = "  第一段\n\n第二段  "
        store.save(content)

        val recreated = ManualCaptureDraftStore(
            ApplicationProvider.getApplicationContext<Context>()
        )

        assertEquals(content, recreated.load())
    }

    @Test
    fun clearingDraftRemovesPersistedContent() {
        store.save("未保存内容")
        store.clear()

        assertEquals("", store.load())
    }
}
