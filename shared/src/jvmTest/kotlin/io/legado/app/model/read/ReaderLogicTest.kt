package io.legado.app.model.read

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.ui.book.read.page.entities.TextChapterDecorator
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {

    @Test
    fun keywordPairAndRegexRulesProduceStableRanges() {
        val text = "他说“你好” HTTP://a.com"
        val rules = listOf(
            ReadColorRule(keyword = "HTTP", foregroundColor = 1),
            ReadColorRule(
                ruleType = ReadColorRule.TYPE_PAIR,
                pairLeft = "“",
                pairRight = "”",
                foregroundColor = 2,
                contentForegroundColor = 3
            ),
            ReadColorRule(
                ruleType = ReadColorRule.TYPE_REGEX,
                pattern = "https?://\\S+",
                backgroundColor = 4
            )
        )

        val matches = ColorRuleMatcher.match(text, rules, "book")
        assertEquals(matches.toString(), 5, matches.size)
        assertEquals(ColorRuleMatch(2, 3, 2, null), matches[0])
        assertEquals(ColorRuleMatch(3, 5, 3, null), matches[1])
        assertEquals(ColorRuleMatch(5, 6, 2, null), matches[2])
        assertEquals(ColorRuleMatch(7, 11, 1, 4), matches[3])
        assertEquals(ColorRuleMatch(11, text.length, null, 4), matches[4])
    }

    @Test
    fun bookScopeAndOverlayMergePerChannel() {
        val matches = ColorRuleMatcher.match(
            text = "角色",
            rules = listOf(
                ReadColorRule(keyword = "角色", foregroundColor = 1, backgroundColor = 2),
                ReadColorRule(bookUrl = "book", keyword = "角色", foregroundColor = 3)
            ),
            bookUrl = "book"
        )
        assertEquals(listOf(ColorRuleMatch(0, 2, 3, 2)), matches)

        assertEquals(
            listOf(
                ColorRuleMatch(0, 2, 1, 2),
                ColorRuleMatch(2, 4, 1, 3),
                ColorRuleMatch(4, 5, 1, 2)
            ),
            ColorRuleMatchMerger.merge(
                listOf(ColorRuleMatch(0, 5, 1, 2)),
                listOf(ColorRuleMatch(2, 4, null, 3))
            )
        )
    }

    @Test
    fun nestedPairsAndInvalidRegexAreSafe() {
        val outer = ReadColorRule(
            ruleType = ReadColorRule.TYPE_PAIR,
            pairLeft = "“",
            pairRight = "”",
            foregroundColor = 1,
            contentForegroundColor = 2
        )
        val inner = ReadColorRule(
            ruleType = ReadColorRule.TYPE_PAIR,
            pairLeft = "「",
            pairRight = "」",
            foregroundColor = 3,
            contentForegroundColor = 4
        )
        val matches = ColorRuleMatcher.match("“外「内」外”", listOf(outer, inner), "book")
        assertEquals(7, matches.size)
        assertEquals(3, matches[2].foregroundColor)
        assertEquals(4, matches[3].foregroundColor)
        assertTrue(
            ColorRuleMatcher.match(
                "abc",
                listOf(ReadColorRule(ruleType = ReadColorRule.TYPE_REGEX, pattern = "[")),
                "book"
            ).isEmpty()
        )
    }

    @Test
    fun highlightReanchorsAndNewestChannelWins() {
        val highlights = listOf(
            BookHighlight(
                time = 1,
                bookUrl = "book",
                chapterPos = 1,
                chapterPosEnd = 5,
                bookText = "bcde",
                foregroundColor = 10,
                backgroundColor = 20
            ),
            BookHighlight(
                time = 2,
                bookUrl = "book",
                chapterPos = 3,
                chapterPosEnd = 7,
                bookText = "defg",
                foregroundColor = 30
            )
        )
        assertEquals(
            listOf(
                ColorRuleMatch(1, 3, 10, 20),
                ColorRuleMatch(3, 5, 30, 20),
                ColorRuleMatch(5, 7, 30, null)
            ),
            BookHighlightMatcher.match("abcdefg", highlights, "book", 0)
        )
        assertEquals(
            listOf(ColorRuleMatch(2, 4, 10, null)),
            BookHighlightMatcher.match(
                "前缀猫咪",
                listOf(
                    BookHighlight(
                        time = 3,
                        bookUrl = "book",
                        chapterPos = 99,
                        chapterPosEnd = 103,
                        bookText = "猫咪",
                        foregroundColor = 10
                    )
                ),
                "book",
                0
            )
        )
    }

    @Test
    fun imageStyleParsingAndLayoutPreserveAspectRatio() {
        val src = "https://example.com/a.jpg,{\"style\":\"width:50%;height:160dp\"}"
        val style = ImageStyleParser.fromSrc(src)
        assertTrue(style is ImageStyleParser.ImageStyle.Size)
        style as ImageStyleParser.ImageStyle.Size
        assertEquals(50f, style.widthPercent)
        assertEquals(160f, style.heightDp)
        assertEquals("https://example.com/a.jpg", ImageStyleParser.sourceWithoutInlineOptions(src))
        assertEquals(ImageStyleParser.ImageStyle.Single, ImageStyleParser.resolve(
            "https://example.com/a.jpg,{\"style\":\"SINGLE\"}", "TEXT"
        ))

        val size = ImageLayoutCalculator.calculate(
            1000f, 500f, 1200f, 1200f,
            ImageStyleParser.ImageStyle.Size(widthPercent = 50f, heightPercent = 25f)
        )
        assertEquals(600f, size.width, 0.01f)
        assertEquals(300f, size.height, 0.01f)
    }

    @Test
    fun palettePresetsStayLowPriorityAndRespectContrast() {
        val palette = ReaderPalette(
            quoteSymbolColor = 1,
            quoteContentColor = 2,
            bracketSymbolColor = 3,
            bracketContentColor = 4,
            punctuationColor = 5,
            specialMarkColor = 6,
            numberColor = 7,
            letterColor = 8
        )
        val rules = ReaderPaletteRules.build(palette)
        assertTrue(rules.any { it.ruleType == ReadColorRule.TYPE_PAIR && it.pairLeft == "“" })
        assertTrue(rules.all { it.sortOrder == ReaderPaletteRules.PRESET_SORT_ORDER })
        val result = ColorRuleMatcher.match(
            "“猫”",
            rules + ReadColorRule(keyword = "猫", foregroundColor = 9),
            "book"
        )
        assertEquals(9, result.single { it.start == 1 }.foregroundColor)
        val generated = FontColorGenerator.next(previous = 1, random = Random(7))
        assertTrue(generated ushr 24 == 0xff)
        assertNotNull(ReaderPalette.nightDefaults().quoteContentColor)
    }

    @Test
    fun inactiveRulesAndMissingHighlightsReturnNoDecoration() {
        assertTrue(
            ColorRuleMatcher.match(
                "猫",
                listOf(ReadColorRule(keyword = "猫", enabled = false)),
                "book"
            ).isEmpty()
        )
        assertTrue(
            BookHighlightMatcher.match(
                "猫",
                listOf(BookHighlight(bookUrl = "other", bookText = "猫", chapterPosEnd = 1)),
                "book",
                0
            ).isEmpty()
        )
        assertNull(ImageStyleParser.parse("width:auto"))
        assertFalse(ImageStyleParser.parse("bogus") is ImageStyleParser.ImageStyle.Size)
    }

    @Test
    fun explicitOptOutSurvivesUntilAModeScopedDraftIsApplied() = kotlinx.coroutines.runBlocking {
        val config = ReadStyleConfig(readerPaletteEnabled = false)
        val draft = ReaderPaletteDraft(config)
        draft.update(ReaderPaletteMode.DAY, ReaderPalette(chapterTitleColor = 7))
        assertFalse(config.readerPaletteEnabled)
        assertTrue(draft.apply { config.applyAppearance(it); Result.success(Unit) }.isSuccess)
        assertTrue(config.readerPaletteEnabled)
        assertEquals(7, config.readerPalette.day.chapterTitleColor)
        assertEquals(ReaderPalette.nightDefaults().chapterTitleColor, config.readerPalette.night.chapterTitleColor)
    }

    @Test
    fun decorationProjectionKeepsIndentPositionAndStylesColumns() {
        val page = TextPage(text = "　猫狗")
        val line = TextLine(text = "　猫狗", chapterPosition = 0, indentSize = 1)
        line.addColumn(TextColumn(0f, 10f, "　"))
        line.addColumn(TextColumn(10f, 20f, "猫"))
        line.addColumn(TextColumn(20f, 30f, "狗"))
        page.addLine(line)

        io.legado.app.ui.book.read.page.entities.TextChapterShared(
            chapterIndex = 0,
            pages = listOf(page),
        ).applyDecorations(
            bookUrl = "book",
            rules = listOf(ReadColorRule(keyword = "猫", foregroundColor = 7)),
            highlights = emptyList()
        )

        val columns = line.columns.filterIsInstance<TextColumn>()
        assertNull(columns[0].foregroundColor)
        assertEquals(7, columns[1].foregroundColor)
        assertNull(columns[2].foregroundColor)
    }
}
