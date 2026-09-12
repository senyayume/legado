package io.legado.app.model.read

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderBackgroundSize { COVER, CONTAIN, ORIGINAL }

@Serializable
enum class ReaderBackgroundPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

@Serializable
enum class ReaderBackgroundBlend { NORMAL, MULTIPLY, LIGHTEN, OVERLAY, SOFT_LIGHT, SCREEN, DARKEN }

/** Pure layer settings shared by the preview and each platform renderer. */
@Serializable
data class ReaderBackgroundSettings(
    /** Image-layer base color. Pure-color mode continues to use the existing bgStr field. */
    val baseColor: Int? = null,
    val enabled: Boolean = true,
    val size: ReaderBackgroundSize = ReaderBackgroundSize.COVER,
    val position: ReaderBackgroundPosition = ReaderBackgroundPosition.CENTER,
    val repeat: Boolean = false,
    val blend: ReaderBackgroundBlend = ReaderBackgroundBlend.NORMAL
)
