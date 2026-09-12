package io.legado.app.model.read

/** ColorTxt 3.8.11 default light/dark surface palettes, without background textures. */
enum class ReaderPalettePreset(val body: Int, val background: Int) {
    PAPER(0xff000000.toInt(), 0xfff4ead7.toInt()),
    DARK(0xffd4d4d4.toInt(), 0xff1e1e1e.toInt());

    fun palette(): ReaderPalette = when (this) {
        PAPER -> ReaderPalette(
            chapterTitleColor = 0xffb88230.toInt(),
            quoteSymbolColor = 0xff267f99.toInt(), quoteContentColor = 0xffa31515.toInt(),
            bracketSymbolColor = 0xff267f99.toInt(), bracketContentColor = 0xff001080.toInt(),
            punctuationColor = 0xff267f99.toInt(), specialMarkColor = 0xfff56c6c.toInt(),
            numberColor = 0xff795e26.toInt(), letterColor = 0xffaf00db.toInt()
        )
        DARK -> ReaderPalette(
            chapterTitleColor = 0xff569cd6.toInt(),
            quoteSymbolColor = 0xff4ec9b0.toInt(), quoteContentColor = 0xffce9178.toInt(),
            bracketSymbolColor = 0xff4ec9b0.toInt(), bracketContentColor = 0xff9cdcfe.toInt(),
            punctuationColor = 0xff4ec9b0.toInt(), specialMarkColor = 0xfff56c6c.toInt(),
            numberColor = 0xffdcdcaa.toInt(), letterColor = 0xffc586c0.toInt()
        )
    }
}
