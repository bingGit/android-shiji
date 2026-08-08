package com.bing.androidvoiceflow.capture.entry

import java.net.URI

internal const val MAX_CAPTURE_CODE_POINTS = 80_000

internal enum class CaptureEntryType {
    Share,
    ProcessText
}

internal data class CaptureProbe(
    val entryType: CaptureEntryType,
    val rawText: String,
    val sourceUrl: String?,
    val titleHint: String?,
    val sourcePackage: String?,
    val receivedAtMillis: Long
)

internal sealed interface CaptureParseResult {
    data class Valid(val probe: CaptureProbe) : CaptureParseResult
    data class Invalid(val reason: String) : CaptureParseResult
}

internal object CaptureIntentParser {
    private val urlCandidate = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val trailingUrlPunctuation = setOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}',
        '\u3002', '\uFF0C', '\uFF1B', '\uFF1A', '\uFF01', '\uFF1F', '\u300B', '\u3009', '\u3011'
    )

    fun parseShare(
        sharedText: CharSequence?,
        titleHint: CharSequence?,
        subjectHint: String?,
        sourcePackage: String?,
        receivedAtMillis: Long = System.currentTimeMillis()
    ): CaptureParseResult {
        val rawText = normalizeRequiredText(sharedText)
            ?: return CaptureParseResult.Invalid("分享内容为空")
        validateLength(rawText)?.let { return it }

        return CaptureParseResult.Valid(
            CaptureProbe(
                entryType = CaptureEntryType.Share,
                rawText = rawText,
                sourceUrl = extractFirstHttpUrl(rawText),
                titleHint = normalizeTitleHint(titleHint) ?: normalizeTitleHint(subjectHint),
                sourcePackage = normalizeOptionalText(sourcePackage),
                receivedAtMillis = receivedAtMillis
            )
        )
    }

    fun parseProcessText(
        selectedText: CharSequence?,
        sourcePackage: String?,
        receivedAtMillis: Long = System.currentTimeMillis()
    ): CaptureParseResult {
        val rawText = normalizeRequiredText(selectedText)
            ?: return CaptureParseResult.Invalid("选中文字为空")
        validateLength(rawText)?.let { return it }

        return CaptureParseResult.Valid(
            CaptureProbe(
                entryType = CaptureEntryType.ProcessText,
                rawText = rawText,
                sourceUrl = null,
                titleHint = null,
                sourcePackage = normalizeOptionalText(sourcePackage),
                receivedAtMillis = receivedAtMillis
            )
        )
    }

    private fun validateLength(text: String): CaptureParseResult.Invalid? {
        val codePointCount = text.codePointCount(0, text.length)
        return if (codePointCount > MAX_CAPTURE_CODE_POINTS) {
            CaptureParseResult.Invalid("内容超过 $MAX_CAPTURE_CODE_POINTS 个字符")
        } else {
            null
        }
    }

    private fun normalizeRequiredText(value: CharSequence?): String? =
        value?.toString()?.trim()?.takeIf(String::isNotEmpty)

    private fun normalizeOptionalText(value: CharSequence?): String? =
        value?.toString()?.trim()?.takeIf(String::isNotEmpty)

    private fun normalizeTitleHint(value: CharSequence?): String? {
        val normalized = normalizeOptionalText(value) ?: return null
        return normalized.takeIf { it.codePointCount(0, it.length) <= 2_000 }
    }

    private fun extractFirstHttpUrl(text: String): String? {
        return urlCandidate.findAll(text)
            .map { it.value.trimEnd { character -> character in trailingUrlPunctuation } }
            .firstOrNull(::isValidHttpUrl)
    }

    private fun isValidHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
