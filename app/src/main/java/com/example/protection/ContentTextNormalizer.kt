package com.example.protection

import java.text.Normalizer
import java.util.Locale

data class NormalizedTextViews(
    val cleanSpaced: String,
    val deobfuscatedSpaced: String,
    val repeatCollapsedSpaced: String,
    val tokens: List<String>,
    val repeatCollapsedTokens: List<String>,
    val spaceCollapsedLong: String,
)

/**
 * Normalizes multilingual text (English, Bengali script, Banglish) and strips common
 * obfuscations (diacritics, zero-width characters, dots/punctuation separators, spaced-out
 * characters, leetspeak, and repeated letters) while preserving Bengali vowel signs/hasant.
 */
object ContentTextNormalizer {
    private val ZERO_WIDTH_REGEX = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF\\u00AD]")
    private val LATIN_COMBINING_MARKS = Regex("[\\u0300-\\u036F\\u1AB0-\\u1AFF\\u1DC0-\\u1DFF]")
    private val IN_TOKEN_SEPARATORS = Regex("[._\\-*'~`^|\\\\/]+")
    private val NON_WORD_SPLIT = Regex("[^\\p{L}\\p{N}\\u0980-\\u09FF+@$!]+")

    fun normalizeForMatching(raw: String): NormalizedTextViews {
        if (raw.isBlank()) {
            return NormalizedTextViews(
                cleanSpaced = "",
                deobfuscatedSpaced = "",
                repeatCollapsedSpaced = "",
                tokens = emptyList(),
                repeatCollapsedTokens = emptyList(),
                spaceCollapsedLong = "",
            )
        }

        // 1. Strip zero-width invisibles & compatibility normalize (fullwidth Latin -> ASCII)
        var text = ZERO_WIDTH_REGEX.replace(raw, "")
        text = Normalizer.normalize(text, Normalizer.Form.NFKC)

        // 2. Decompose & strip only Latin combining diacritics so Bengali vowel signs stay intact
        text = Normalizer.normalize(text, Normalizer.Form.NFKD)
        text = LATIN_COMBINING_MARKS.replace(text, "")
        text = Normalizer.normalize(text, Normalizer.Form.NFC)

        // 3. Unify Bengali digits ০-৯ -> 0-9 & lowercase
        text = unifyBengaliDigits(text).lowercase(Locale.ROOT)

        // 4. Tokenize & strip internal punctuation separators (e.g., p.o.r.n -> porn)
        val rawTokens =
            text.split(NON_WORD_SPLIT).mapNotNull { piece ->
                val stripped = IN_TOKEN_SEPARATORS.replace(piece, "").trim()
                stripped.takeIf { it.isNotEmpty() }
            }

        val cleanTokens = mergeSingleCharRuns(rawTokens)
        val deobfuscatedTokens = cleanTokens.map { applyLeetspeakIfApplicable(it) }
        val repeatCollapsedTokens = deobfuscatedTokens.map { collapseRepeats(it) }

        val cleanSpaced = cleanTokens.joinToString(" ")
        val deobfuscatedSpaced = deobfuscatedTokens.joinToString(" ")
        val repeatCollapsedSpaced = repeatCollapsedTokens.joinToString(" ")
        val spaceCollapsedLong = deobfuscatedTokens.joinToString("")

        return NormalizedTextViews(
            cleanSpaced = cleanSpaced,
            deobfuscatedSpaced = deobfuscatedSpaced,
            repeatCollapsedSpaced = repeatCollapsedSpaced,
            tokens = (cleanTokens + deobfuscatedTokens).distinct(),
            repeatCollapsedTokens = repeatCollapsedTokens.distinct(),
            spaceCollapsedLong = spaceCollapsedLong,
        )
    }

    fun normalizeKeyword(rawKeyword: String): String {
        val views = normalizeForMatching(rawKeyword)
        return views.cleanSpaced
    }

    fun collapseRepeats(input: String): String {
        if (input.length <= 1) return input
        val sb = StringBuilder(input.length)
        var prev = '\u0000'
        for (ch in input) {
            if (ch != prev) {
                sb.append(ch)
                prev = ch
            }
        }
        return sb.toString()
    }

    private fun unifyBengaliDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '\u09E6'..'\u09EF') {
                sb.append(('0'.code + (ch.code - '\u09E6'.code)).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Joins runs of >= 3 consecutive 1-character tokens (e.g., "p o r n" -> "porn",
     * "d n s s h i e l d t e s t w o r d" -> "dnsshieldtestword") without merging normal words.
     */
    private fun mergeSingleCharRuns(tokens: List<String>): List<String> {
        if (tokens.size < 3) return tokens
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            if (isSingleAsciiOrDigitToken(tokens[i])) {
                var end = i
                while (end < tokens.size && isSingleAsciiOrDigitToken(tokens[end])) {
                    end++
                }
                if (end - i >= 3) {
                    val joined = StringBuilder(end - i)
                    for (j in i until end) {
                        joined.append(tokens[j])
                    }
                    out.add(joined.toString())
                    i = end
                    continue
                }
            }
            out.add(tokens[i])
            i++
        }
        return out
    }

    private fun isSingleAsciiOrDigitToken(token: String): Boolean {
        if (token.length != 1) return false
        val ch = token[0]
        return (ch in 'a'..'z') || (ch in '0'..'9')
    }

    /**
     * Converts leetspeak characters inside tokens that contain at least one Latin letter or
     * symbol (@, $, !), preserving pure numbers like "18+".
     */
    private fun applyLeetspeakIfApplicable(token: String): String {
        val hasLetterOrSymbol =
            token.any { it in 'a'..'z' || it == '@' || it == '$' || it == '!' }
        if (!hasLetterOrSymbol) return token

        val sb = StringBuilder(token.length)
        for (ch in token) {
            when (ch) {
                '0' -> sb.append('o')
                '1', '!' -> sb.append('i')
                '3' -> sb.append('e')
                '4', '@' -> sb.append('a')
                '5', '$' -> sb.append('s')
                '7' -> sb.append('t')
                '8' -> sb.append('b')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
