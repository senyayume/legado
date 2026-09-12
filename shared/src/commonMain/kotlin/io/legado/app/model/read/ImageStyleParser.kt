package io.legado.app.model.read

import io.legado.app.constant.AppPattern
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 解析图片 URL 的有限内联样式，不接触网络和缓存。 */
object ImageStyleParser {

    sealed class ImageStyle {
        data object Default : ImageStyle()
        data object Full : ImageStyle()
        data object Single : ImageStyle()
        data object Text : ImageStyle()

        data class Size(
            val widthPercent: Float? = null,
            val heightPercent: Float? = null,
            val widthDp: Float? = null,
            val heightDp: Float? = null
        ) : ImageStyle() {
            val hasWidth: Boolean get() = widthPercent != null || widthDp != null
            val hasHeight: Boolean get() = heightPercent != null || heightDp != null
            val isEmpty: Boolean get() = !hasWidth && !hasHeight
        }
    }

    fun fromSrc(src: String): ImageStyle? {
        val matcher = AppPattern.urlParamPattern.find(src) ?: return null
        val style = runCatching {
            val element = KS_JSON.parseToJsonElement(src.substring(matcher.range.last + 1))
            val value = (element as? JsonObject)?.get("style") as? JsonPrimitive
            if (value?.isString == true) value.contentOrNull else null
        }.getOrNull()
        return parse(style)
    }

    fun sourceWithoutInlineOptions(src: String): String {
        val matcher = AppPattern.urlParamPattern.find(src)
        return if (matcher == null) src else src.substring(0, matcher.range.first)
    }

    fun parse(style: String?): ImageStyle? {
        if (style.isNullOrBlank()) return null
        return when (style.trim().uppercase()) {
            "DEFAULT", "AUTO" -> ImageStyle.Default
            "FULL" -> ImageStyle.Full
            "SINGLE" -> ImageStyle.Single
            "TEXT" -> ImageStyle.Text
            else -> parseSize(style)
        }
    }

    fun resolve(src: String, globalStyle: String?): ImageStyle =
        fromSrc(src) ?: parse(globalStyle) ?: ImageStyle.Default

    private fun parseSize(style: String): ImageStyle.Size? {
        var widthPercent: Float? = null
        var heightPercent: Float? = null
        var widthDp: Float? = null
        var heightDp: Float? = null
        style.split(';').forEach { declaration ->
            val separator = declaration.indexOf(':')
            if (separator < 0) return@forEach
            val key = declaration.substring(0, separator).trim().lowercase()
            if (key != "width" && key != "height") return@forEach
            val dimension = parseDimension(declaration.substring(separator + 1).trim()) ?: return@forEach
            if (key == "width") {
                if (dimension.isPercent) widthPercent = dimension.value else widthDp = dimension.value
            } else {
                if (dimension.isPercent) heightPercent = dimension.value else heightDp = dimension.value
            }
        }
        return ImageStyle.Size(widthPercent, heightPercent, widthDp, heightDp)
            .takeUnless { it.isEmpty }
    }

    private data class Dimension(val value: Float, val isPercent: Boolean)

    private fun parseDimension(value: String): Dimension? {
        if (value.isBlank() || value.equals("auto", ignoreCase = true)) return null
        val normalized = value.lowercase()
        val percent = normalized.endsWith('%')
        val dp = normalized.endsWith("dp")
        val px = normalized.endsWith("px")
        val numberText = when {
            percent -> normalized.dropLast(1).trim()
            dp || px -> normalized.dropLast(2).trim()
            else -> normalized.trim()
        }
        val number = numberText.toFloatOrNull() ?: return null
        if (!number.isFinite() || number <= 0f) return null
        return Dimension(number, percent)
    }
}
