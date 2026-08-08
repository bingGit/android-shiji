package com.bing.androidvoiceflow.capture.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntentParserTest {
    @Test
    fun `share preserves internal text and extracts url`() {
        val result = CaptureIntentParser.parseShare(
            sharedText = "  一段分享文字\nhttps://example.com/article?id=3  ",
            titleHint = "  文章标题  ",
            subjectHint = "备用标题",
            sourcePackage = "com.example.reader",
            receivedAtMillis = 42L
        ) as CaptureParseResult.Valid

        assertEquals("一段分享文字\nhttps://example.com/article?id=3", result.probe.rawText)
        assertEquals("https://example.com/article?id=3", result.probe.sourceUrl)
        assertEquals("文章标题", result.probe.titleHint)
        assertEquals("com.example.reader", result.probe.sourcePackage)
        assertEquals(42L, result.probe.receivedAtMillis)
    }

    @Test
    fun `share falls back to subject when title is blank`() {
        val result = CaptureIntentParser.parseShare(
            sharedText = "https://example.com",
            titleHint = "  ",
            subjectHint = "主题标题",
            sourcePackage = null
        ) as CaptureParseResult.Valid

        assertEquals("主题标题", result.probe.titleHint)
    }

    @Test
    fun `share strips sentence punctuation from extracted url`() {
        val result = CaptureIntentParser.parseShare(
            sharedText = "原文地址：https://example.com/read?q=1。",
            titleHint = null,
            subjectHint = null,
            sourcePackage = null
        ) as CaptureParseResult.Valid

        assertEquals("https://example.com/read?q=1", result.probe.sourceUrl)
    }

    @Test
    fun `share ignores non-http schemes`() {
        val result = CaptureIntentParser.parseShare(
            sharedText = "obsidian://open?vault=notes",
            titleHint = null,
            subjectHint = null,
            sourcePackage = null
        ) as CaptureParseResult.Valid

        assertNull(result.probe.sourceUrl)
    }

    @Test
    fun `oversized untrusted title hint is ignored without dropping share text`() {
        val result = CaptureIntentParser.parseShare(
            sharedText = "正文 https://example.com",
            titleHint = "题".repeat(2_001),
            subjectHint = null,
            sourcePackage = null
        ) as CaptureParseResult.Valid

        assertNull(result.probe.titleHint)
        assertEquals("正文 https://example.com", result.probe.rawText)
    }

    @Test
    fun `process text preserves line breaks without deriving source url`() {
        val result = CaptureIntentParser.parseProcessText(
            selectedText = "  第一行\n第二行 https://example.com  ",
            sourcePackage = "com.android.chrome",
            receivedAtMillis = 7L
        ) as CaptureParseResult.Valid

        assertEquals("第一行\n第二行 https://example.com", result.probe.rawText)
        assertNull(result.probe.sourceUrl)
        assertEquals(CaptureEntryType.ProcessText, result.probe.entryType)
    }

    @Test
    fun `blank input is rejected`() {
        assertTrue(
            CaptureIntentParser.parseShare(null, null, null, null) is CaptureParseResult.Invalid
        )
        assertTrue(
            CaptureIntentParser.parseProcessText("  \n ", null) is CaptureParseResult.Invalid
        )
    }

    @Test
    fun `length limit counts unicode code points`() {
        val accepted = "😀".repeat(MAX_CAPTURE_CODE_POINTS)
        val rejected = accepted + "😀"

        assertTrue(
            CaptureIntentParser.parseProcessText(accepted, null) is CaptureParseResult.Valid
        )
        assertTrue(
            CaptureIntentParser.parseProcessText(rejected, null) is CaptureParseResult.Invalid
        )
    }
}
