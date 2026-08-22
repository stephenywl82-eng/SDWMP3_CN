package com.sdw.music.player.util

import android.icu.text.Transliterator

/**
 * Chinese → Pinyin initial for A-Z indexing.
 *
 * Uses Android's built-in ICU Transliterator ("Han-Latin") which converts
 * Han characters to their pinyin reading. Reliable across all devices
 * (android.icu is available since API 24, which is our minSdk).
 *
 * Fallback chain:
 *   - ASCII letter → its uppercase
 *   - CJK char    → pinyin first letter (cached)
 *   - anything else → '#'
 */
object PinyinUtils {
    private const val FALLBACK = '#'

    private val hanLatin: Transliterator? by lazy {
        try {
            // "Han-Latin" 转写带声调（如 "阿"→"ā"），再链式 "Latin-ASCII" 去掉
            // 声调标记（"ā"→"a"），否则首字符不是 ASCII 字母会误判为 '#'。
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        } catch (e: Exception) {
            null
        }
    }

    // Per-char cache to keep list building fast (grouping runs on the UI thread)
    private val cache = HashMap<Char, Char>()

    fun getInitial(ch: Char): Char = when {
        ch in 'A'..'Z' -> ch
        ch in 'a'..'z' -> ch.uppercaseChar()
        ch.code in 0x4E00..0x9FFF -> pinyinInitial(ch)
        else -> FALLBACK
    }

    fun getInitial(s: String): Char = s.firstOrNull()?.let { getInitial(it) } ?: FALLBACK

    private fun pinyinInitial(ch: Char): Char {
        cache[ch]?.let { return it }
        val result = runCatching {
            val pinyin = hanLatin?.transliterate(ch.toString()).orEmpty()
            pinyin.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }?.uppercaseChar() ?: FALLBACK
        }.getOrDefault(FALLBACK)
        cache[ch] = result
        return result
    }
}
