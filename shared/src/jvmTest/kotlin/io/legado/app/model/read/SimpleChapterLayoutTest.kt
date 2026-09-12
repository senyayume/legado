package io.legado.app.model.read

import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.provider.ChapterContentParserShared
import io.legado.app.ui.book.read.page.provider.ImageResolver
import io.legado.app.ui.book.read.page.provider.ImageSize
import io.legado.app.ui.book.read.page.provider.ImgData
import io.legado.app.ui.book.read.page.provider.ParsedParagraph
import io.legado.app.ui.book.read.page.provider.SimpleChapterLayout
import io.legado.app.ui.book.read.page.provider.SimpleTextMeasurer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleChapterLayoutTest {

    @Test
    fun inlineImageStyleOverridesGlobalStyleAndStaysInline() = runBlocking {
        val page = layout(
            parsedParagraph = ParsedParagraph(
                text = "A${ChapterContentParserShared.srcReplaceChar}B",
                images = listOf(
                    ImgData(
                        src = "https://example.com/a.png,{\"style\":\"TEXT\"}",
                        style = "",
                        onclick = "",
                    )
                ),
            ),
            globalStyle = "SINGLE",
        ).single()

        val image = page.lines.flatMap { it.columns }.filterIsInstance<ImageColumn>().single()
        assertTrue("style=${image.imageStyle}", image.imageStyle is ImageStyleParser.ImageStyle.Text)
        assertEquals("https://example.com/a.png,{\"style\":\"TEXT\"}", image.src)
        assertFalse("lines=${page.lines}", page.lines.single { it.columns.contains(image) }.isImage)
    }

    @Test
    fun inlineSingleImageUsesBlockLineEvenWithTextGlobalStyle() = runBlocking {
        val page = layout(
            parsedParagraph = ParsedParagraph(
                text = ChapterContentParserShared.srcReplaceChar,
                images = listOf(
                    ImgData(
                        src = "https://example.com/a.png",
                        style = "SINGLE",
                        onclick = "",
                    )
                ),
            ),
            globalStyle = "TEXT",
        ).single()

        val image = page.lines.flatMap { it.columns }.filterIsInstance<ImageColumn>().single()
        assertEquals(ImageStyleParser.ImageStyle.Single, image.imageStyle)
        assertTrue(page.lines.single { it.columns.contains(image) }.isImage)
    }

    @Test
    fun singleImageSeparatesSurroundingTextAndKeepsOriginalUrl() = runBlocking {
        val url = "https://example.com/a.png,{\"style\":\"SINGLE\"}"
        val pages = layout(
            parsedParagraph = ParsedParagraph(
                text = "A${ChapterContentParserShared.srcReplaceChar}B",
                images = listOf(ImgData(src = url, style = "", onclick = "")),
            ),
            globalStyle = "TEXT",
        )

        assertEquals(3, pages.size)
        assertEquals("A", pages.first().lines.single().text)
        assertEquals("B", pages.last().lines.single().text)
        val imageLine = pages[1].lines.single()
        val image = imageLine.columns.filterIsInstance<ImageColumn>().single()
        assertTrue(imageLine.isImage)
        assertEquals(ImageStyleParser.ImageStyle.Single, image.imageStyle)
        assertEquals(url, image.src)
        assertEquals(40f, imageLine.lineTop, 0.01f)
        assertEquals(60f, imageLine.lineBottom, 0.01f)
    }

    @Test
    fun consecutiveSingleImagesOwnPagesWithoutEmptyPages() = runBlocking {
        val urls = listOf("https://example.com/first.png", "https://example.com/second.png")
        val pages = layout(
            ParsedParagraph(
                text = ChapterContentParserShared.srcReplaceChar.repeat(2),
                images = urls.map { ImgData(src = it, style = "SINGLE", onclick = "") },
            ),
            globalStyle = "TEXT",
        )
        assertEquals(2, pages.size)
        assertEquals(urls, pages.map { page ->
            val line = page.lines.single()
            assertTrue(line.isImage)
            line.columns.filterIsInstance<ImageColumn>().single().src
        })
    }

    @Test
    fun inlineSingleInlineMixPreservesTextAndImageOrder() = runBlocking {
        val marker = ChapterContentParserShared.srcReplaceChar
        val urls = listOf("https://example.com/a.png", "https://example.com/b.png", "https://example.com/c.png")
        val pages = layout(
            ParsedParagraph(
                text = "A${marker}B${marker}C${marker}D",
                images = urls.mapIndexed { index, url ->
                    ImgData(src = url, style = if (index == 1) "SINGLE" else "TEXT", onclick = "")
                },
            ),
            globalStyle = "SINGLE",
        )
        assertEquals(3, pages.size)
        assertEquals(urls, pages.flatMap { it.lines }.flatMap { it.columns }
            .filterIsInstance<ImageColumn>().map { it.src })
        assertTrue(pages[1].lines.single().isImage)
        assertEquals("AB", pages.first().lines.joinToString("") { it.text }.replace(marker, ""))
        assertEquals("CD", pages.last().lines.joinToString("") { it.text }.replace(marker, ""))
    }

    @Test
    fun fullImageFillsWidthAndKeepsOriginalUrl() = runBlocking {
        val url = "https://example.com/a.png,{\"style\":\"FULL\"}"
        val page = layout(
            parsedParagraph = ParsedParagraph(
                text = ChapterContentParserShared.srcReplaceChar,
                images = listOf(ImgData(src = url, style = "", onclick = "")),
            ),
            globalStyle = "TEXT",
        ).single()

        val line = page.lines.single()
        val image = line.columns.filterIsInstance<ImageColumn>().single()
        assertTrue(line.isImage)
        assertEquals(ImageStyleParser.ImageStyle.Full, image.imageStyle)
        assertEquals(url, image.src)
        assertEquals(100f, image.end - image.start, 0.01f)
        assertEquals(100f, line.lineBottom - line.lineTop, 0.01f)
    }

    private suspend fun layout(
        parsedParagraph: ParsedParagraph,
        globalStyle: String,
    ) = SimpleChapterLayout(
        measurer = SimpleTextMeasurer(textSizePx = 10f),
        visibleWidth = 100,
        visibleHeight = 100,
        paddingLeft = 0,
        paddingTop = 0,
        textHeight = 10f,
        descent = 2f,
        lineSpacingExtra = 1f,
        paragraphSpacing = 0,
        titleTopSpacing = 0,
        titleBottomSpacing = 0,
        endPadding = 0,
        paragraphIndent = "",
        textFullJustify = false,
        srcReplaceChar = ChapterContentParserShared.srcReplaceChar,
    ).layout(
        displayTitle = "",
        contents = listOf(parsedParagraph.text),
        chapterIndex = 0,
        chapterSize = 1,
        parsedParagraphs = listOf(parsedParagraph),
        imageResolver = object : ImageResolver {
            override suspend fun getImageSize(src: String): ImageSize = ImageSize(20, 20)
        },
        imageStyle = globalStyle,
    )
}
