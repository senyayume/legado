package io.legado.app.model.read

import io.legado.app.data.entities.ReadColorRule
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** 阅读器语义配色；正文和背景基础色仍由现有阅读配置持有。 */
@Serializable
data class ReaderPalette(
    var chapterTitleColor: Int? = null,
    var quoteSymbolColor: Int? = null,
    var quoteContentColor: Int? = null,
    var bracketSymbolColor: Int? = null,
    var bracketContentColor: Int? = null,
    var bracketPairsEnabled: Boolean = true,
    var punctuationColor: Int? = null,
    var specialMarkColor: Int? = null,
    var numberColor: Int? = null,
    var letterColor: Int? = null,
    var searchResultColor: Int? = null,
    var searchResultBackgroundColor: Int? = null,
    var bookmarkColor: Int? = null,
    var bookmarkBackgroundColor: Int? = null,
    var annotationColor: Int? = null,
    var annotationBackgroundColor: Int? = null
) {

    fun resetReaderFields(defaults: ReaderPalette): ReaderPalette = copy(
        chapterTitleColor = defaults.chapterTitleColor,
        quoteSymbolColor = defaults.quoteSymbolColor,
        quoteContentColor = defaults.quoteContentColor,
        bracketSymbolColor = defaults.bracketSymbolColor,
        bracketContentColor = defaults.bracketContentColor,
        bracketPairsEnabled = defaults.bracketPairsEnabled,
        punctuationColor = defaults.punctuationColor,
        specialMarkColor = defaults.specialMarkColor,
        numberColor = defaults.numberColor,
        letterColor = defaults.letterColor
    )

    fun resetHighlightFields(defaults: ReaderPalette): ReaderPalette = copy(
        searchResultColor = defaults.searchResultColor,
        searchResultBackgroundColor = defaults.searchResultBackgroundColor,
        bookmarkColor = defaults.bookmarkColor,
        bookmarkBackgroundColor = defaults.bookmarkBackgroundColor,
        annotationColor = defaults.annotationColor,
        annotationBackgroundColor = defaults.annotationBackgroundColor
    )

    companion object {
        fun dayDefaults() = ReaderPalette(
            chapterTitleColor = 0xffb77b24.toInt(), quoteSymbolColor = 0xff2b7895.toInt(),
            quoteContentColor = 0xffb32124.toInt(), bracketSymbolColor = 0xff2b7895.toInt(),
            bracketContentColor = 0xff253b9a.toInt(), punctuationColor = 0xff2b7895.toInt(),
            specialMarkColor = 0xfff06060.toInt(), numberColor = 0xff806020.toInt(),
            letterColor = 0xffa000c0.toInt(), searchResultColor = 0xffff9800.toInt(),
            searchResultBackgroundColor = 0x66ff9800, bookmarkColor = 0xffe91e63.toInt(),
            annotationColor = 0xff8bc34a.toInt()
        )

        fun nightDefaults() = ReaderPalette(
            chapterTitleColor = 0xffffc866.toInt(), quoteSymbolColor = 0xff73c7de.toInt(),
            quoteContentColor = 0xffff837a.toInt(), bracketSymbolColor = 0xff73c7de.toInt(),
            bracketContentColor = 0xff9daeff.toInt(), punctuationColor = 0xff73c7de.toInt(),
            specialMarkColor = 0xffff8c8c.toInt(), numberColor = 0xffffcf72.toInt(),
            letterColor = 0xffd889ff.toInt(), searchResultColor = 0xffffb74d.toInt(),
            searchResultBackgroundColor = 0x66ffb74d, bookmarkColor = 0xffff80ab.toInt(),
            annotationColor = 0xffc5e1a5.toInt()
        )

        fun eInkDefaults() = ReaderPalette(
            chapterTitleColor = 0xff6d4c1d.toInt(), quoteSymbolColor = 0xff2f6173.toInt(),
            quoteContentColor = 0xff7e1c1c.toInt(), bracketSymbolColor = 0xff2f6173.toInt(),
            bracketContentColor = 0xff1d2e74.toInt(), punctuationColor = 0xff2f6173.toInt(),
            specialMarkColor = 0xff8b3535.toInt(), numberColor = 0xff604a1b.toInt(),
            letterColor = 0xff6b197f.toInt(), searchResultColor = 0xff8a5200.toInt(),
            searchResultBackgroundColor = 0x668a5200, bookmarkColor = 0xff8c1642.toInt(),
            annotationColor = 0xff4b6a21.toInt()
        )
    }
}

@Serializable
data class ReaderPaletteSet(
    var day: ReaderPalette = ReaderPalette.dayDefaults(),
    var night: ReaderPalette = ReaderPalette.nightDefaults(),
    var eInk: ReaderPalette = ReaderPalette.eInkDefaults()
) {
    fun forMode(mode: ReaderPaletteMode): ReaderPalette = when (mode) {
        ReaderPaletteMode.DAY -> day
        ReaderPaletteMode.NIGHT -> night
        ReaderPaletteMode.EINK -> eInk
    }

    fun copyForMode(mode: ReaderPaletteMode): ReaderPalette = forMode(mode).copy()

    fun setForMode(mode: ReaderPaletteMode, value: ReaderPalette) {
        when (mode) {
            ReaderPaletteMode.DAY -> day = value
            ReaderPaletteMode.NIGHT -> night = value
            ReaderPaletteMode.EINK -> eInk = value
        }
    }
}

enum class ReaderPaletteMode { DAY, NIGHT, EINK }

/** 将语义配色转换为渲染时低优先级内置规则，不写回 Room。 */
object ReaderPaletteRules {
    const val PRESET_SORT_ORDER = Int.MAX_VALUE
    private val quotePairs = listOf("“" to "”", "‘" to "’", "「" to "」", "『" to "』", "《" to "》", "〈" to "〉")
    private val bracketPairs = listOf("（" to "）", "(" to ")", "【" to "】", "[" to "]")

    fun build(palette: ReaderPalette): List<ReadColorRule> = buildList {
        addPairPresets("quote", quotePairs, palette.quoteSymbolColor, palette.quoteContentColor)
        addPairPresets("bracket", bracketPairs, palette.bracketSymbolColor, palette.bracketContentColor, palette.bracketPairsEnabled)
        addRegexPreset("url", "(?:https?://|www\\.)[^\\s，。！？；：、]+", palette.specialMarkColor)
        addRegexPreset("email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", palette.specialMarkColor)
        addRegexPreset("phone", "(?:\\+?86[- ]?)?1[3-9]\\d{9}", palette.numberColor)
        addRegexPreset("date", "\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}日?", palette.numberColor)
        addRegexPreset("amount", "(?:¥|￥|\\$)\\s?\\d+(?:\\.\\d+)?(?:元|万|亿)?", palette.numberColor)
        addRegexPreset("punctuation", "[，。！？；：、,.!?;:…]+", palette.punctuationColor)
        addRegexPreset("special-mark", "(?:【[^】\\r\\n]{1,40}】|\\[[^\\]\\r\\n]{1,40}\\]|<[^>\\r\\n]{1,40}>)", palette.specialMarkColor)
        addRegexPreset("number", "[0-9０-９]+", palette.numberColor)
        addRegexPreset("letter", "[A-Za-zＡ-Ｚａ-ｚ]+", palette.letterColor)
    }

    private fun MutableList<ReadColorRule>.addPairPresets(
        group: String,
        pairs: List<Pair<String, String>>,
        symbolColor: Int?,
        contentColor: Int?,
        enabled: Boolean = true
    ) {
        if (!enabled || (symbolColor == null && contentColor == null)) return
        pairs.forEachIndexed { index, (left, right) ->
            add(
                ReadColorRule(
                    ruleType = ReadColorRule.TYPE_PAIR,
                    pairLeft = left,
                    pairRight = right,
                    foregroundColor = symbolColor,
                    contentForegroundColor = contentColor,
                    contentEnabled = contentColor != null,
                    sortOrder = PRESET_SORT_ORDER,
                    presetId = "$group-pair-$index"
                )
            )
        }
    }

    private fun MutableList<ReadColorRule>.addRegexPreset(id: String, pattern: String, color: Int?) {
        color ?: return
        add(
            ReadColorRule(
                ruleType = ReadColorRule.TYPE_REGEX,
                pattern = pattern,
                foregroundColor = color,
                sortOrder = PRESET_SORT_ORDER,
                presetId = id
            )
        )
    }
}

object ReaderPalettePreview {
    fun match(text: String, palette: ReaderPalette): List<ColorRuleMatch> =
        ColorRuleMatcher.match(text, ReaderPaletteRules.build(palette), "")
}

object ReaderPaletteColorPolicy {
    private val alphaFields = setOf("background", "searchResultBackground", "bookmarkBackground", "annotationBackground")
    fun preservesAlpha(fieldKey: String?): Boolean = fieldKey != null && fieldKey in alphaFields
    fun normalize(fieldKey: String?, color: Int): Int =
        if (preservesAlpha(fieldKey)) color else (color and 0x00ffffff) or 0xff000000.toInt()
}

/** 返回不透明且相对背景可读的随机字体色。 */
object FontColorGenerator {
    const val MIN_CONTRAST_RATIO = 4.5
    private const val MAX_RANDOM_ATTEMPTS = 64
    private val fallbackColors = intArrayOf(0xff000000.toInt(), 0xff111827.toInt(), 0xff374151.toInt(), 0xff6b7280.toInt(), 0xffd1d5db.toInt(), 0xfff3f4f6.toInt(), 0xffffffff.toInt())

    fun next(previous: Int? = null, random: Random = Random.Default, background: Int = 0xfff3ead5.toInt()): Int {
        val opaqueBackground = background or 0xff000000.toInt()
        repeat(MAX_RANDOM_ATTEMPTS) {
            val red = random.nextInt(0x20, 0xe0)
            val green = random.nextInt(0x20, 0xe0)
            val blue = random.nextInt(0x20, 0xe0)
            val color = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            if (color != previous && contrastRatio(color, opaqueBackground) >= MIN_CONTRAST_RATIO) return color
        }
        fallbackColors.firstOrNull { it != previous && contrastRatio(it, opaqueBackground) >= MIN_CONTRAST_RATIO }?.let { return it }
        return if (previous != 0xff000000.toInt()) 0xff000000.toInt() else 0xffffffff.toInt()
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return channel(color ushr 16 and 0xff) * 0.2126 +
            channel(color ushr 8 and 0xff) * 0.7152 + channel(color and 0xff) * 0.0722
    }
}
