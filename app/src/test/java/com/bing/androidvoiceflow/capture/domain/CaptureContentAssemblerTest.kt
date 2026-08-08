package com.bing.androidvoiceflow.capture.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureContentAssemblerTest {
    @Test
    fun `excerpt keeps comment and original text in separate sections`() {
        val content = CaptureContentAssembler.assembleSingle(
            SingleCaptureContent(
                captureType = CaptureType.Excerpt,
                rawText = "原文第一行\n原文第二行",
                comment = "我的判断",
                sourceUrl = null,
                titleHint = null
            )
        )

        assertEquals(
            """
            ## 我的想法

            我的判断

            ## 原文摘录

            ~~~ text
            原文第一行
            原文第二行
            ~~~
            """.trimIndent(),
            content
        )
    }

    @Test
    fun `plain text fence is longer than every tilde run in source`() {
        val fenced = CaptureContentAssembler.fencedPlainText(
            content = "前文\n~~~~\n后文",
            minimumFenceLength = 3
        )

        assertTrue(fenced.startsWith("~~~~~ text\n"))
        assertTrue(fenced.endsWith("\n~~~~~"))
        assertTrue(fenced.contains("\n~~~~\n"))
    }

    @Test
    fun `article preserves raw share text and emits source once`() {
        val content = CaptureContentAssembler.assembleSingle(
            SingleCaptureContent(
                captureType = CaptureType.Article,
                rawText = "文章标题 https://example.com/read",
                comment = null,
                sourceUrl = "https://example.com/read",
                titleHint = "文章标题"
            )
        )

        assertTrue(content.contains("文章标题 https://example.com/read"))
        assertTrue(content.contains("- 标题提示：文章标题"))
        assertTrue(content.contains("- URL：https://example.com/read"))
        assertEquals(1, "## 来源".toRegex().findAll(content).count())
    }

    @Test
    fun `invalid source scheme is not emitted`() {
        val content = CaptureContentAssembler.assembleSingle(
            SingleCaptureContent(
                captureType = CaptureType.Article,
                rawText = "分享内容",
                comment = null,
                sourceUrl = "javascript:alert(1)",
                titleHint = null
            )
        )

        assertFalse(content.contains("## 来源"))
        assertFalse(content.contains("javascript:"))
    }

    @Test
    fun `manual text is assembled as a note instead of shared content`() {
        val content = CaptureContentAssembler.assembleSingle(
            SingleCaptureContent(
                captureType = CaptureType.ManualText,
                rawText = "  主动写下的一段想法  ",
                comment = null,
                sourceUrl = null,
                titleHint = null
            )
        )

        assertEquals("## 拾记\n\n主动写下的一段想法", content)
        assertFalse(content.contains("分享内容"))
    }

    @Test
    fun `reading session preserves block order and numbers each type independently`() {
        val content = CaptureContentAssembler.assembleReadingSession(
            ReadingSessionContent(
                blocks = listOf(
                    ReadingContentBlock(3, ReadingBlockType.Excerpt, "摘录二"),
                    ReadingContentBlock(1, ReadingBlockType.Excerpt, "摘录一"),
                    ReadingContentBlock(4, ReadingBlockType.Comment, "想法二"),
                    ReadingContentBlock(2, ReadingBlockType.Comment, "想法一")
                ),
                sourceUrl = "https://example.com/article",
                titleHint = "来源标题",
                rawShareText = "来源标题 https://example.com/article"
            )
        )

        val excerptOne = content.indexOf("### 摘录 1")
        val commentOne = content.indexOf("### 我的想法 1")
        val excerptTwo = content.indexOf("### 摘录 2")
        val commentTwo = content.indexOf("### 我的想法 2")
        assertTrue(excerptOne < commentOne)
        assertTrue(commentOne < excerptTwo)
        assertTrue(excerptTwo < commentTwo)
        assertEquals(1, "## 来源".toRegex().findAll(content).count())
        assertEquals(1, "## 分享文字".toRegex().findAll(content).count())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reading session rejects duplicate positions`() {
        CaptureContentAssembler.assembleReadingSession(
            ReadingSessionContent(
                blocks = listOf(
                    ReadingContentBlock(1, ReadingBlockType.Excerpt, "一"),
                    ReadingContentBlock(1, ReadingBlockType.Comment, "二")
                ),
                sourceUrl = null,
                titleHint = null,
                rawShareText = null
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `assembled content rejects service limit overflow`() {
        CaptureContentAssembler.assembleSingle(
            SingleCaptureContent(
                captureType = CaptureType.SharedText,
                rawText = "文".repeat(MAX_ASSEMBLED_CONTENT_CODE_POINTS),
                comment = null,
                sourceUrl = null,
                titleHint = null
            )
        )
    }
}
