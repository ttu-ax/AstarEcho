package com.astraecho.rhythm.engine

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Computes 3D-style trapezoidal perspective projection for a 4/5/6-lane falling rhythm track.
 * Adapts automatically to both landscape phones (16:9 / 20:9) and tablets (4:3 / 16:10),
 * and seamlessly extends the perspective trapezoid through the judgment line into the keyboard keys.
 */
data class TrackProjection(
    val width: Float,
    val height: Float,
    val laneCount: Int,
    val tiltDegrees: Float
) {
    private val clampedTilt: Float = tiltDegrees.coerceIn(0f, 60f)
    private val tiltRatio: Float = clampedTilt / 60f

    val centerX: Float = width * 0.5f

    // Track starts near top edge in landscape for maximum sight-reading distance
    val topY: Float = height * (0.01f + 0.03f * (1f - tiltRatio))
    // Judgment seam sits directly on the top edge of the angled 3D keyboard keys (no gap!)
    val judgeY: Float = height * 0.825f
    val bottomY: Float = height * 0.985f

    // Adaptive width: ergonomic on both 4:3 tablets and 20:9 wide phones
    val bottomWidth: Float = if (width >= height) {
        min(width * 0.78f, height * 1.24f)
    } else {
        width * 0.94f
    }

    val topWidth: Float = bottomWidth * (1.0f - 0.74f * sin(tiltRatio * (PI / 2.0)).toFloat())

    // Perspective depth factor: 1.0f = linear (flat), larger = stronger foreshortening
    private val depthK: Float = 1.0f + 2.55f * tiltRatio

    val judgeLeft: Float = centerX - bottomWidth * 0.5f
    val judgeRight: Float = centerX + bottomWidth * 0.5f
    val topLeft: Float = centerX - topWidth * 0.5f
    val topRight: Float = centerX + topWidth * 0.5f

    // Seamless keyboard bottom edge continues the exact perspective slope past judgeY to bottomY
    private val keyDepthRatio: Float = (bottomY - judgeY) / (judgeY - topY).coerceAtLeast(1f)
    val keyBottomWidth: Float = bottomWidth + (bottomWidth - topWidth) * keyDepthRatio
    val keyBottomLeft: Float = centerX - keyBottomWidth * 0.5f
    val keyBottomRight: Float = centerX + keyBottomWidth * 0.5f

    val laneWidthAtJudge: Float = bottomWidth / laneCount.coerceAtLeast(1)

    fun linearToScreenProgress(linearProgress: Float): Float {
        if (tiltRatio < 0.001f) return linearProgress
        if (linearProgress <= 0f) return 0f
        if (linearProgress <= 1f) {
            return linearProgress / (linearProgress + depthK * (1f - linearProgress))
        }
        return 1f + (linearProgress - 1f) * depthK * 0.65f
    }

    fun yAt(linearProgress: Float): Float {
        val sp = linearToScreenProgress(linearProgress)
        return topY + (judgeY - topY) * sp
    }

    fun trackWidthAt(linearProgress: Float): Float {
        val sp = linearToScreenProgress(linearProgress)
        return topWidth + (bottomWidth - topWidth) * sp
    }

    fun scaleAt(linearProgress: Float): Float {
        return (trackWidthAt(linearProgress) / bottomWidth).coerceAtLeast(0.18f)
    }

    fun xAt(lanePos: Float, linearProgress: Float): Float {
        val w = trackWidthAt(linearProgress)
        val left = centerX - w * 0.5f
        val frac = lanePos / laneCount.toFloat()
        return left + w * frac
    }

    /**
     * X coordinate along the seamless bottom lip of the angled keyboard keys (at bottomY).
     */
    fun keyBottomXAt(lanePos: Float): Float {
        val frac = lanePos / laneCount.toFloat()
        return keyBottomLeft + keyBottomWidth * frac
    }

    fun laneIndexForTouchX(x: Float): Int {
        val frac = ((x - judgeLeft) / bottomWidth).coerceIn(0f, 0.9999f)
        return (frac * laneCount).toInt().coerceIn(0, laneCount - 1)
    }

    /**
     * Perspective-exact (x, y) touch-to-lane mapping so 4K, 5K, and 6K slanted 3D key seams
     * align pixel-for-pixel with the user's fingers even near the bottom edge of the screen!
     */
    fun laneIndexForTouch(x: Float, y: Float): Int {
        val clampedY = y.coerceIn(judgeY * 0.7f, bottomY)
        val widthAtY = if (clampedY >= judgeY) {
            val t = ((clampedY - judgeY) / (bottomY - judgeY).coerceAtLeast(1f)).coerceIn(0f, 1f)
            bottomWidth + (keyBottomWidth - bottomWidth) * t
        } else {
            val t = ((clampedY - topY) / (judgeY - topY).coerceAtLeast(1f)).coerceIn(0f, 1f)
            topWidth + (bottomWidth - topWidth) * t
        }
        val leftAtY = centerX - widthAtY * 0.5f
        val frac = ((x - leftAtY) / widthAtY).coerceIn(0f, 0.9999f)
        return (frac * laneCount).toInt().coerceIn(0, laneCount - 1)
    }
}
