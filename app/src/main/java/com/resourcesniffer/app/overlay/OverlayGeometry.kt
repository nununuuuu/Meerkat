package com.resourcesniffer.app.overlay

/** WindowManager coordinates use distance from the right edge for x. */
internal data class OverlayCorner(val right: Boolean, val bottom: Boolean)

internal data class OverlayPosition(
    val panelX: Int,
    val panelY: Int,
    val bubbleX: Int,
    val bubbleY: Int,
    val corner: OverlayCorner,
)

internal object OverlayGeometry {
    fun openAtBubble(
        bubbleX: Int,
        bubbleY: Int,
        panelWidth: Int,
        panelHeight: Int,
        bubbleSize: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): OverlayPosition {
        val half = bubbleSize / 2
        val corner = OverlayCorner(
            right = bubbleX + half < screenWidth / 2,
            bottom = bubbleY + half >= screenHeight / 2,
        )
        val panelX = if (corner.right) bubbleX + half else bubbleX - panelWidth + half
        val panelY = if (corner.bottom) bubbleY - panelHeight + half else bubbleY + half
        return place(panelX, panelY, corner, panelWidth, panelHeight, bubbleSize, screenWidth, screenHeight)
    }

    fun place(
        desiredPanelX: Int,
        desiredPanelY: Int,
        corner: OverlayCorner,
        panelWidth: Int,
        panelHeight: Int,
        bubbleSize: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): OverlayPosition {
        val half = bubbleSize / 2
        val minX = half
        val maxX = (screenWidth - panelWidth - half).coerceAtLeast(minX)
        val minY = half
        val maxY = (screenHeight - panelHeight - half).coerceAtLeast(minY)
        val panelX = desiredPanelX.coerceIn(minX, maxX)
        val panelY = desiredPanelY.coerceIn(minY, maxY)
        val fittedCorner = OverlayCorner(
            right = when {
                desiredPanelX < minX -> false
                desiredPanelX > maxX -> true
                else -> corner.right
            },
            bottom = when {
                desiredPanelY < minY -> true
                desiredPanelY > maxY -> false
                else -> corner.bottom
            },
        )
        return OverlayPosition(
            panelX = panelX,
            panelY = panelY,
            bubbleX = if (fittedCorner.right) panelX - half else panelX + panelWidth - half,
            bubbleY = if (fittedCorner.bottom) panelY + panelHeight - half else panelY - half,
            corner = fittedCorner,
        )
    }
}
