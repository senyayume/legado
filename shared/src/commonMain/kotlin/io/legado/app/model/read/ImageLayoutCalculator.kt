package io.legado.app.model.read

import kotlin.math.min

/** 图片尺寸纯几何计算，保留原图比例并限制在可视区域内。 */
object ImageLayoutCalculator {

    data class Size(val width: Float, val height: Float)

    fun calculate(
        rawWidth: Float,
        rawHeight: Float,
        visibleWidth: Float,
        visibleHeight: Float,
        style: ImageStyleParser.ImageStyle,
        density: Float = 1f
    ): Size {
        if (rawWidth <= 0f || rawHeight <= 0f || visibleWidth <= 0f || visibleHeight <= 0f) {
            return Size(0f, 0f)
        }
        return when (style) {
            ImageStyleParser.ImageStyle.Full -> fitInside(rawWidth, rawHeight, visibleWidth, visibleHeight, true)
            is ImageStyleParser.ImageStyle.Size -> {
                val requestedWidth = style.widthPercent?.let { visibleWidth * it / 100f }
                    ?: style.widthDp?.let { it * density }
                val requestedHeight = style.heightPercent?.let { visibleHeight * it / 100f }
                    ?: style.heightDp?.let { it * density }
                fitInside(
                    rawWidth,
                    rawHeight,
                    min(requestedWidth ?: visibleWidth, visibleWidth),
                    min(requestedHeight ?: visibleHeight, visibleHeight),
                    true
                )
            }
            ImageStyleParser.ImageStyle.Default,
            ImageStyleParser.ImageStyle.Single,
            ImageStyleParser.ImageStyle.Text -> fitInside(rawWidth, rawHeight, visibleWidth, visibleHeight, false)
        }
    }

    private fun fitInside(
        rawWidth: Float,
        rawHeight: Float,
        maxWidth: Float,
        maxHeight: Float,
        allowUpscale: Boolean
    ): Size {
        val scale = min(maxWidth / rawWidth, maxHeight / rawHeight)
        val finalScale = if (allowUpscale) scale else min(scale, 1f)
        return Size(rawWidth * finalScale, rawHeight * finalScale)
    }
}
