package io.legado.app.model.read

import org.junit.Assert.*
import org.junit.Test

class ReaderBackgroundLayoutTest {
    @Test fun containKeepsWholeImageAtSelectedEdge() {
        val result = ReaderBackgroundLayout.calculate(400, 200, 200f, 400f,
            ReaderBackgroundSettings(size = ReaderBackgroundSize.CONTAIN,
                position = ReaderBackgroundPosition.BOTTOM_RIGHT))!!
        assertEquals(0.5f, result.scale)
        assertEquals(0f, result.left)
        assertEquals(300f, result.top)
    }

    @Test fun coverFillsPortraitWithoutDistortingImage() {
        val result = ReaderBackgroundLayout.calculate(400, 200, 200f, 400f,
            ReaderBackgroundSettings())!!
        assertEquals(2f, result.scale)
        assertEquals(-300f, result.left)
        assertEquals(0f, result.top)
    }

    @Test fun invalidViewportDoesNotDraw() {
        assertNull(ReaderBackgroundLayout.calculate(0, 200, 200f, 400f, ReaderBackgroundSettings()))
        assertNull(ReaderBackgroundLayout.calculate(400, 200, Float.NaN, 400f, ReaderBackgroundSettings()))
    }
}
