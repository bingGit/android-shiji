package com.bing.androidvoiceflow.capture.domain

import java.net.URI
import kotlin.math.max

internal const val MAX_ASSEMBLED_CONTENT_CODE_POINTS = 100_000

internal data class SingleCaptureContent(
    val captureType: CaptureType,
    val rawText: String,
    val comment: String?,
    val sourceUrl: String?,
    val titleHint: String?
)

internal data class ReadingSessionContent(
    val blocks: List<ReadingContentBlock>,
    val sourceUrl: String?,
    val titleHint: String?,
    val rawShareText: String?
)

internal object CaptureContentAssembler {
    fun assembleSingle(input: SingleCaptureContent): String {
        val rawText = input.rawText.trim()
        require(rawText.isNotEmpty()) { "捕获内容不能为空" }

        val sections = buildList {
            input.comment.normalizedOrNull()?.let { comment ->
                add("## 我的想法\n\n$comment")
            }

            when (input.captureType) {
                CaptureType.Excerpt -> {
                    add("## 原文摘录\n\n${fencedPlainText(rawText, minimumFenceLength = 3)}")
                }

                CaptureType.Article -> {
                    add("## 分享文字\n\n${fencedPlainText(rawText, minimumFenceLength = 4)}")
                    sourceSection(input.titleHint, input.sourceUrl)?.let(::add)
                }

                CaptureType.SharedText -> {
                    add("## 分享内容\n\n${fencedPlainText(rawText, minimumFenceLength = 4)}")
                }

                CaptureType.ManualText -> {
                    add("## 拾记\n\n$rawText")
                }
            }
        }

        return validateAssembledContent(sections.joinToString("\n\n"))
    }

    fun assembleReadingSession(input: ReadingSessionContent): String {
        require(input.blocks.isNotEmpty()) { "阅读摘录至少需要一个内容块" }
        val orderedBlocks = input.blocks.sortedBy(ReadingContentBlock::position)
        require(orderedBlocks.map(ReadingContentBlock::position).distinct().size == orderedBlocks.size) {
            "阅读摘录内容块位置不能重复"
        }

        var excerptNumber = 0
        var commentNumber = 0
        val timelineSections = orderedBlocks.map { block ->
            val content = block.content.trim()
            require(content.isNotEmpty()) { "阅读摘录内容块不能为空" }

            when (block.type) {
                ReadingBlockType.Excerpt -> {
                    excerptNumber += 1
                    "### 摘录 $excerptNumber\n\n${fencedPlainText(content, minimumFenceLength = 3)}"
                }

                ReadingBlockType.Comment -> {
                    commentNumber += 1
                    "### 我的想法 $commentNumber\n\n$content"
                }
            }
        }

        val sections = buildList {
            input.rawShareText.normalizedOrNull()?.let { rawShareText ->
                add("## 分享文字\n\n${fencedPlainText(rawShareText, minimumFenceLength = 4)}")
            }
            add("## 摘录与思考\n\n${timelineSections.joinToString("\n\n")}")
            sourceSection(input.titleHint, input.sourceUrl)?.let(::add)
        }

        return validateAssembledContent(sections.joinToString("\n\n"))
    }

    internal fun fencedPlainText(content: String, minimumFenceLength: Int): String {
        require(minimumFenceLength >= 3)
        val longestTildeRun = Regex("~+").findAll(content)
            .maxOfOrNull { it.value.length }
            ?: 0
        val fence = "~".repeat(max(minimumFenceLength, longestTildeRun + 1))
        return "$fence text\n$content\n$fence"
    }

    private fun sourceSection(titleHint: String?, sourceUrl: String?): String? {
        val lines = buildList {
            titleHint.normalizedOrNull()?.let { add("- 标题提示：$it") }
            sourceUrl.validHttpUrlOrNull()?.let { add("- URL：$it") }
        }
        return lines.takeIf(List<String>::isNotEmpty)?.joinToString(
            separator = "\n",
            prefix = "## 来源\n\n"
        )
    }

    private fun validateAssembledContent(content: String): String {
        require(content.isNotBlank()) { "组装内容不能为空" }
        require(content.codePointCount(0, content.length) <= MAX_ASSEMBLED_CONTENT_CODE_POINTS) {
            "组装内容超过 $MAX_ASSEMBLED_CONTENT_CODE_POINTS 个字符"
        }
        return content
    }

    private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String?.validHttpUrlOrNull(): String? {
        val value = normalizedOrNull() ?: return null
        return runCatching {
            val uri = URI(value)
            value.takeIf {
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
            }
        }.getOrNull()
    }
}
