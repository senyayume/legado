package io.legado.app.model.read

/** Geometry shared by the reader and preview; independent of text pagination. */
data class ReaderBackgroundLayout(val scale: Float, val left: Float, val top: Float) {
    companion object {
        fun calculate(
            imageWidth: Int, imageHeight: Int, width: Float, height: Float,
            settings: ReaderBackgroundSettings,
        ): ReaderBackgroundLayout? {
            if (imageWidth <= 0 || imageHeight <= 0 || !width.isFinite() ||
                !height.isFinite() || width <= 0 || height <= 0) return null
            val scale = when (settings.size) {
                ReaderBackgroundSize.COVER -> maxOf(width / imageWidth, height / imageHeight)
                ReaderBackgroundSize.CONTAIN -> minOf(width / imageWidth, height / imageHeight)
                ReaderBackgroundSize.ORIGINAL -> 1f
            }
            val x = when (settings.position) {
                ReaderBackgroundPosition.TOP_LEFT, ReaderBackgroundPosition.CENTER_LEFT,
                ReaderBackgroundPosition.BOTTOM_LEFT -> 0f
                ReaderBackgroundPosition.TOP_RIGHT, ReaderBackgroundPosition.CENTER_RIGHT,
                ReaderBackgroundPosition.BOTTOM_RIGHT -> 1f
                else -> 0.5f
            }
            val y = when (settings.position) {
                ReaderBackgroundPosition.TOP_LEFT, ReaderBackgroundPosition.TOP_CENTER,
                ReaderBackgroundPosition.TOP_RIGHT -> 0f
                ReaderBackgroundPosition.BOTTOM_LEFT, ReaderBackgroundPosition.BOTTOM_CENTER,
                ReaderBackgroundPosition.BOTTOM_RIGHT -> 1f
                else -> 0.5f
            }
            return ReaderBackgroundLayout(scale, (width - imageWidth * scale) * x,
                (height - imageHeight * scale) * y)
        }
    }
}
