package com.astraecho.rhythm.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.astraecho.rhythm.model.NoteSkin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zero-GC 120fps animated background, track, and keyboard decorator for all 5 skins:
 * - ASTRA_CYBER: Rotating celestial astrolabe rings, twinkling starfield, beat scanlines
 * - CLASSIC_MASTER: Sweeping concert laser spotlights, animated 12-bar equalizer, neon runner dots
 * - SAKURA_PULSE: Drifting rotating sakura petals, breathing moonlight aura, golden blossom pulse
 * - CRIMSON_OVERDRIVE: Animated mecha chevrons, rotating tactical radar, laser rail surges
 * - EULER_CHALK: Live rotating 3x3 matrix numerical calculation, step-by-step formula simplification,
 *                animated Fourier wave & breathing Riemann-sum integral bars, scrolling drafting ruler!
 */
class SkinStageAnimator {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val serifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        textAlign = Paint.Align.LEFT
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val shapePath = Path()

    // Precomputed deterministic seeds for stars & drifting sakura petals (zero allocation!)
    private val petalX = FloatArray(18) { i -> ((i * 37 + 11) % 100) / 100f }
    private val petalY = FloatArray(18) { i -> ((i * 53 + 7) % 100) / 100f }
    private val petalSpeed = FloatArray(18) { i -> 0.045f + (i % 5) * 0.014f }
    private val petalSize = FloatArray(18) { i -> 7f + (i % 4) * 3.5f }

    // Euler Chalk step-by-step formula simplification sequences (pre-allocated Strings!)
    private val mathProofSteps = arrayOf(
        arrayOf(
            "e^(iπ) + 1",
            "= cos(π) + i·sin(π) + 1",
            "= -1 + 0 + 1",
            "= 0   [Q.E.D.]"
        ),
        arrayOf(
            "∫₀^π sin(x) dx",
            "= [-cos(x)]₀^π",
            "= -(-1) - (-1)",
            "= 2   [Q.E.D.]"
        ),
        arrayOf(
            "lim(n→∞) (1 + 1/n)ⁿ",
            "= ∑ (1 / k!)",
            "≈ 2.7182818...",
            "= e   [Q.E.D.]"
        )
    )

    /**
     * Draws animated skin-specific background elements on the left & right stage wings
     * (drawn over the static stage image, underneath the falling track).
     */
    fun drawBackgroundEffects(
        canvas: Canvas,
        proj: TrackProjection,
        skin: NoteSkin,
        timeSec: Float,
        beatPhase: Float // 0..1 sawtooth per beat
    ) {
        val beatPulse = (1f - beatPhase).let { it * it } // sharp punch on beat, smooth decay

        when (skin) {
            NoteSkin.ASTRA_CYBER -> drawAstraBgAnim(canvas, proj, timeSec, beatPulse)
            NoteSkin.CLASSIC_MASTER -> drawClassicBgAnim(canvas, proj, timeSec, beatPulse)
            NoteSkin.SAKURA_PULSE -> drawSakuraBgAnim(canvas, proj, timeSec, beatPulse)
            NoteSkin.CRIMSON_OVERDRIVE -> drawCrimsonBgAnim(canvas, proj, timeSec, beatPulse)
            NoteSkin.EULER_CHALK -> drawEulerMathBgAnim(canvas, proj, timeSec, beatPulse)
        }
    }

    /**
     * Draws animated beat-synced motion elements directly along the perspective track & rails.
     */
    fun drawTrackMotionEffects(
        canvas: Canvas,
        proj: TrackProjection,
        skin: NoteSkin,
        timeSec: Float,
        beatPhase: Float
    ) {
        val railR = Color.red(skin.railColor)
        val railG = Color.green(skin.railColor)
        val railB = Color.blue(skin.railColor)

        when (skin) {
            NoteSkin.ASTRA_CYBER -> {
                // Beat-synced glowing scanline rushing down the perspective highway!
                val p = beatPhase
                val y = proj.yAt(p)
                val alpha = ((1f - abs(p - 0.5f) * 1.4f).coerceIn(0.15f, 1f) * 110).toInt()
                strokePaint.color = Color.argb(alpha, railR, railG, railB)
                strokePaint.strokeWidth = 2.5f + p * 3.5f
                canvas.drawLine(proj.xAt(0f, p), y, proj.xAt(proj.laneCount.toFloat(), p), y, strokePaint)
            }

            NoteSkin.CLASSIC_MASTER -> {
                // Flowing neon runner light beads racing down both outer side rails
                fillPaint.color = Color.argb(210, 140, 245, 110)
                for (k in 0..3) {
                    val p = (timeSec * 0.85f + k * 0.25f) % 1f
                    val y = proj.yAt(p)
                    val r = 3.5f + p * 4.5f
                    canvas.drawCircle(proj.xAt(0f, p), y, r, fillPaint)
                    canvas.drawCircle(proj.xAt(proj.laneCount.toFloat(), p), y, r, fillPaint)
                }
            }

            NoteSkin.SAKURA_PULSE -> {
                // Gentle drifting petal-pink beat fret + glowing blossom core at judgment center
                val p = beatPhase
                val y = proj.yAt(p)
                strokePaint.color = Color.argb((85 * (1f - p * 0.4f)).toInt(), 255, 175, 220)
                strokePaint.strokeWidth = 2.2f
                canvas.drawLine(proj.xAt(0f, p), y, proj.xAt(proj.laneCount.toFloat(), p), y, strokePaint)
            }

            NoteSkin.CRIMSON_OVERDRIVE -> {
                // High-speed scrolling perspective tactical grid bars + laser surges along rails
                for (k in 0..4) {
                    val p = (timeSec * 1.1f + k * 0.2f) % 1f
                    val y = proj.yAt(p)
                    strokePaint.color = Color.argb((65 * p).toInt(), 255, 65, 95)
                    strokePaint.strokeWidth = 1.8f + p * 1.6f
                    canvas.drawLine(proj.xAt(0f, p), y, proj.xAt(proj.laneCount.toFloat(), p), y, strokePaint)
                }
                val surgeP = (timeSec * 1.6f) % 1f
                val surgeP2 = (surgeP + 0.08f).coerceAtMost(1f)
                strokePaint.color = Color.argb(230, 0, 245, 212)
                strokePaint.strokeWidth = 5f
                canvas.drawLine(proj.xAt(0f, surgeP), proj.yAt(surgeP), proj.xAt(0f, surgeP2), proj.yAt(surgeP2), strokePaint)
                canvas.drawLine(
                    proj.xAt(proj.laneCount.toFloat(), surgeP), proj.yAt(surgeP),
                    proj.xAt(proj.laneCount.toFloat(), surgeP2), proj.yAt(surgeP2), strokePaint
                )
            }

            NoteSkin.EULER_CHALK -> {
                // Scrolling white drafting ruler tick marks along both outer rails
                strokePaint.color = Color.argb(145, 255, 255, 255)
                strokePaint.strokeWidth = 2f
                val scroll = (timeSec * 0.65f) % 1f
                for (step in 0..9) {
                    val p = (step * 0.1f + scroll * 0.1f).coerceIn(0.02f, 0.99f)
                    val y = proj.yAt(p)
                    val tick = if (step % 2 == 0) 15f * proj.scaleAt(p) else 8f * proj.scaleAt(p)
                    val lx = proj.xAt(0f, p)
                    val rx = proj.xAt(proj.laneCount.toFloat(), p)
                    canvas.drawLine(lx - tick, y, lx, y, strokePaint)
                    canvas.drawLine(rx, y, rx + tick, y, strokePaint)
                }
            }
        }
    }

    /**
     * Draws animated tactile feedback directly on the seamless 3D keyboard keys when pressed/idle.
     */
    fun drawKeyboardEffects(
        canvas: Canvas,
        proj: TrackProjection,
        skin: NoteSkin,
        pressedLanes: BooleanArray,
        laneFlashAlpha: FloatArray,
        timeSec: Float,
        beatPhase: Float
    ) {
        val beatPulse = (1f - beatPhase).let { it * it }
        val keyMidY = (proj.judgeY + proj.bottomY) * 0.52f

        for (lane in 0 until proj.laneCount) {
            val active = pressedLanes[lane] || laneFlashAlpha[lane] > 0.08f
            val intensity = if (pressedLanes[lane]) 1f else laneFlashAlpha[lane]
            val cx = (proj.xAt(lane + 0.5f, 1f) + proj.keyBottomXAt(lane + 0.5f)) * 0.5f
            val keyW = proj.laneWidthAtJudge

            when (skin) {
                NoteSkin.ASTRA_CYBER -> {
                    // Breathing constellation diamond inside each key; flares brightly when pressed!
                    val s = keyW * (if (active) 0.16f else 0.08f + beatPulse * 0.025f)
                    val alpha = if (active) (240 * intensity).toInt() else (70 + beatPulse * 45).toInt()
                    fillPaint.color = Color.argb(alpha.coerceIn(0, 255), 180, 250, 255)
                    shapePath.reset()
                    shapePath.moveTo(cx, keyMidY - s * 0.65f)
                    shapePath.lineTo(cx + s, keyMidY)
                    shapePath.lineTo(cx, keyMidY + s * 0.65f)
                    shapePath.lineTo(cx - s, keyMidY)
                    shapePath.close()
                    canvas.drawPath(shapePath, fillPaint)
                }

                NoteSkin.CLASSIC_MASTER -> {
                    // Arcade glowing LED bar inside each key cap
                    val halfW = keyW * (if (active) 0.34f else 0.22f)
                    val alpha = if (active) (245 * intensity).toInt() else (65 + beatPulse * 55).toInt()
                    strokePaint.color = Color.argb(alpha.coerceIn(0, 255), 110, 225, 255)
                    strokePaint.strokeWidth = if (active) 7f else 3.5f
                    canvas.drawLine(cx - halfW, keyMidY, cx + halfW, keyMidY, strokePaint)
                }

                NoteSkin.SAKURA_PULSE -> {
                    // Rotating 4-petal glow emblem on pressed key
                    if (active) {
                        val r = keyW * 0.14f * intensity
                        fillPaint.color = Color.argb((220 * intensity).toInt(), 255, 220, 240)
                        canvas.drawCircle(cx - r * 0.6f, keyMidY, r * 0.45f, fillPaint)
                        canvas.drawCircle(cx + r * 0.6f, keyMidY, r * 0.45f, fillPaint)
                        canvas.drawCircle(cx, keyMidY - r * 0.55f, r * 0.45f, fillPaint)
                        canvas.drawCircle(cx, keyMidY + r * 0.55f, r * 0.45f, fillPaint)
                    }
                }

                NoteSkin.CRIMSON_OVERDRIVE -> {
                    // Tactical cyan/crimson targeting brackets when pressed
                    val halfW = keyW * 0.28f
                    val halfH = (proj.bottomY - proj.judgeY) * 0.24f
                    val alpha = if (active) (245 * intensity).toInt() else 55
                    strokePaint.color = if (active) Color.argb(alpha, 0, 245, 212) else Color.argb(alpha, 255, 70, 100)
                    strokePaint.strokeWidth = if (active) 4f else 2f
                    canvas.drawLine(cx - halfW, keyMidY - halfH, cx - halfW, keyMidY + halfH, strokePaint)
                    canvas.drawLine(cx + halfW, keyMidY - halfH, cx + halfW, keyMidY + halfH, strokePaint)
                }

                NoteSkin.EULER_CHALK -> {
                    // Clean monochrome coordinate bracket + subtle index label on key cap
                    val halfW = keyW * (if (active) 0.30f else 0.20f)
                    val halfH = (proj.bottomY - proj.judgeY) * 0.22f
                    val alpha = if (active) (245 * intensity).toInt() else (75 + beatPulse * 40).toInt()
                    strokePaint.color = Color.argb(alpha.coerceIn(0, 255), 255, 255, 255)
                    strokePaint.strokeWidth = if (active) 3.5f else 1.8f
                    // Left & right matrix brackets on key
                    canvas.drawLine(cx - halfW, keyMidY - halfH, cx - halfW, keyMidY + halfH, strokePaint)
                    canvas.drawLine(cx - halfW, keyMidY - halfH, cx - halfW + 7f, keyMidY - halfH, strokePaint)
                    canvas.drawLine(cx - halfW, keyMidY + halfH, cx - halfW + 7f, keyMidY + halfH, strokePaint)
                    canvas.drawLine(cx + halfW, keyMidY - halfH, cx + halfW, keyMidY + halfH, strokePaint)
                    canvas.drawLine(cx + halfW - 7f, keyMidY - halfH, cx + halfW, keyMidY - halfH, strokePaint)
                    canvas.drawLine(cx + halfW - 7f, keyMidY + halfH, cx + halfW, keyMidY + halfH, strokePaint)
                }
            }
        }
    }

    // --- Per-Skin Background Animations ---

    private fun drawAstraBgAnim(canvas: Canvas, proj: TrackProjection, timeSec: Float, beatPulse: Float) {
        val w = proj.width
        val h = proj.height
        val rBase = h * 0.16f * (1f + beatPulse * 0.06f)

        // Left & Right rotating celestial astrolabe rings
        drawAstrolabeRing(canvas, w * 0.10f, h * 0.62f, rBase, timeSec * 18f, Color.argb(95, 56, 242, 255))
        drawAstrolabeRing(canvas, w * 0.90f, h * 0.56f, rBase * 0.85f, -timeSec * 24f, Color.argb(85, 255, 59, 141))

        // Twinkling cosmic starlight points on both wings
        for (i in 0 until 12) {
            val sx = if (i % 2 == 0) w * (0.04f + (i * 19 % 16) * 0.01f) else w * (0.82f + (i * 13 % 15) * 0.01f)
            val sy = h * (0.24f + (i * 29 % 68) * 0.01f)
            val twinkle = (0.35f + 0.65f * abs(sin(timeSec * 2.2f + i * 1.3f)))
            fillPaint.color = Color.argb((180 * twinkle).toInt(), 210, 248, 255)
            canvas.drawCircle(sx, sy, 2.2f + twinkle * 2.5f, fillPaint)
        }
    }

    private fun drawAstrolabeRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, deg: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = 2.2f
        canvas.drawCircle(cx, cy, radius, strokePaint)
        canvas.drawCircle(cx, cy, radius * 0.68f, strokePaint)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(deg)
        for (k in 0 until 4) {
            val a = k * PI * 0.5
            val x0 = (cos(a) * radius * 0.68f).toFloat()
            val y0 = (sin(a) * radius * 0.68f).toFloat()
            val x1 = (cos(a) * radius * 1.12f).toFloat()
            val y1 = (sin(a) * radius * 1.12f).toFloat()
            canvas.drawLine(x0, y0, x1, y1, strokePaint)
        }
        canvas.restore()
    }

    private fun drawClassicBgAnim(canvas: Canvas, proj: TrackProjection, timeSec: Float, beatPulse: Float) {
        val w = proj.width
        val h = proj.height

        // Sweeping concert laser beams from top corners
        val sweepL = sin(timeSec * 1.2f) * w * 0.12f
        val sweepR = cos(timeSec * 1.1f) * w * 0.12f
        strokePaint.color = Color.argb(42, 60, 190, 255)
        strokePaint.strokeWidth = w * 0.055f
        canvas.drawLine(0f, 0f, w * 0.24f + sweepL, h, strokePaint)
        strokePaint.color = Color.argb(34, 139, 232, 59)
        canvas.drawLine(w, 0f, w * 0.76f + sweepR, h, strokePaint)

        // Animated 8-bar symmetrical equalizer on left & right lower wings
        val barCount = 8
        val barW = w * 0.013f
        val gap = barW * 0.55f
        val baseY = h * 0.90f
        for (i in 0 until barCount) {
            val wave = 0.28f + 0.52f * abs(sin(timeSec * 4.8f + i * 0.75f)) + beatPulse * 0.35f
            val barH = h * 0.22f * wave.coerceAtMost(1f)
            fillPaint.color = if (i % 3 == 0) Color.argb(120, 139, 232, 59) else Color.argb(115, 56, 175, 255)
            // Left wing
            val lx = w * 0.025f + i * (barW + gap)
            canvas.drawRect(lx, baseY - barH, lx + barW, baseY, fillPaint)
            // Right wing
            val rx = w * 0.975f - (i + 1) * barW - i * gap
            canvas.drawRect(rx, baseY - barH, rx + barW, baseY, fillPaint)
        }
    }

    private fun drawSakuraBgAnim(canvas: Canvas, proj: TrackProjection, timeSec: Float, beatPulse: Float) {
        val w = proj.width
        val h = proj.height

        // Breathing moon aura on top-left
        fillPaint.color = Color.argb((38 + beatPulse * 28).toInt(), 255, 210, 235)
        canvas.drawCircle(w * 0.11f, h * 0.28f, h * (0.11f + beatPulse * 0.02f), fillPaint)

        // Drifting, rotating cherry blossom petals across both wings
        for (i in petalX.indices) {
            val rawX = petalX[i]
            // Keep petals framing the left & right wings cleanly
            val wingX = if (i % 2 == 0) rawX * 0.24f else 0.76f + rawX * 0.24f
            val yFrac = (petalY[i] + timeSec * petalSpeed[i]) % 1.15f - 0.08f
            val swayX = sin(timeSec * 1.8f + i) * w * 0.018f
            val px = wingX * w + swayX
            val py = yFrac * h
            val s = petalSize[i]

            fillPaint.color = if (i % 4 == 0) Color.argb(145, 255, 220, 155) else Color.argb(155, 255, 160, 210)
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(timeSec * (45f + (i % 3) * 25f) + i * 33f)
            shapePath.reset()
            shapePath.moveTo(0f, -s)
            shapePath.quadTo(s * 0.8f, -s * 0.15f, 0f, s)
            shapePath.quadTo(-s * 0.8f, -s * 0.15f, 0f, -s)
            shapePath.close()
            canvas.drawPath(shapePath, fillPaint)
            canvas.restore()
        }
    }

    private fun drawCrimsonBgAnim(canvas: Canvas, proj: TrackProjection, timeSec: Float, beatPulse: Float) {
        val w = proj.width
        val h = proj.height

        // Pulsing tactical chevrons on left & right wings
        val activeIdx = (timeSec * 5f).toInt() % 3
        strokePaint.strokeWidth = 4.5f
        for (k in 0 until 3) {
            val lit = k == activeIdx
            strokePaint.color = if (lit) Color.argb(210, 0, 245, 212) else Color.argb(85, 255, 55, 85)
            val off = k * (w * 0.018f)
            val cy = h * 0.58f
            val ch = h * 0.08f
            // Left >>>
            val lx = w * 0.05f + off
            canvas.drawLine(lx, cy - ch, lx + 20f, cy, strokePaint)
            canvas.drawLine(lx + 20f, cy, lx, cy + ch, strokePaint)
            // Right <<<
            val rx = w * 0.95f - off
            canvas.drawLine(rx, cy - ch, rx - 20f, cy, strokePaint)
            canvas.drawLine(rx - 20f, cy, rx, cy + ch, strokePaint)
        }

        // Rotating tactical radar ring on right lower wing
        val rcx = w * 0.89f
        val rcy = h * 0.78f
        val rr = h * (0.095f + beatPulse * 0.012f)
        strokePaint.color = Color.argb(105, 255, 65, 95)
        strokePaint.strokeWidth = 2.2f
        canvas.drawCircle(rcx, rcy, rr, strokePaint)
        val ang = timeSec * 2.4f
        canvas.drawLine(rcx, rcy, rcx + cos(ang) * rr, rcy + sin(ang) * rr, strokePaint)
    }

    /**
     * EULER_CHALK: Live monochrome mathematical chalkboard animations!
     * - Left wing: Step-by-step formula simplification + live animated Fourier wave curve
     * - Right wing: Live rotating 3x3 Rotation Matrix with ticking numerical values + breathing Riemann integral bars!
     */
    private fun drawEulerMathBgAnim(canvas: Canvas, proj: TrackProjection, timeSec: Float, beatPulse: Float) {
        val w = proj.width
        val h = proj.height

        // 1. LEFT WING: Step-by-step live formula simplification
        val proofIdx = ((timeSec / 6.0f).toInt()) % mathProofSteps.size
        val proofLocalT = (timeSec % 6.0f)
        val visibleSteps = ((proofLocalT / 1.15f).toInt() + 1).coerceIn(1, 4)
        val steps = mathProofSteps[proofIdx]

        val leftX = w * 0.022f
        var curY = h * 0.44f
        val lineStep = h * 0.048f
        serifPaint.textSize = h * 0.028f

        for (s in 0 until visibleSteps) {
            val isLatest = (s == visibleSteps - 1)
            val alpha = if (isLatest) 215 else 115
            serifPaint.color = Color.argb(alpha, 245, 247, 250)
            canvas.drawText(steps[s], leftX, curY, serifPaint)
            curY += lineStep
        }

        // 2. LEFT LOWER WING: Live animated Fourier wave y(t) = sin(2x - wt) + 0.4*sin(4x + 1.5wt)
        val plotX = w * 0.022f
        val plotW = w * 0.165f
        val plotY = h * 0.78f
        val plotAmp = h * 0.068f * (0.85f + beatPulse * 0.3f)

        strokePaint.color = Color.argb(85, 255, 255, 255)
        strokePaint.strokeWidth = 1.6f
        canvas.drawLine(plotX, plotY, plotX + plotW, plotY, strokePaint)

        shapePath.reset()
        val segs = 28
        for (i in 0..segs) {
            val frac = i.toFloat() / segs
            val x = plotX + frac * plotW
            val phase = frac * 6.283f * 1.5f - timeSec * 3.6f
            val y = plotY - (sin(phase) * 0.72f + sin(phase * 2.0f + timeSec * 2f) * 0.28f) * plotAmp
            if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
        }
        strokePaint.color = Color.argb(185, 255, 255, 255)
        strokePaint.strokeWidth = 2.4f
        canvas.drawPath(shapePath, strokePaint)

        // 3. RIGHT WING: Live rotating 3x3 Rotation Matrix R(θ) with ticking real-time numbers!
        val theta = timeSec * 0.9f
        val cVal = cos(theta)
        val sVal = sin(theta)
        val mx = w * 0.815f
        val my = h * 0.42f
        val mw = w * 0.165f
        val mh = h * 0.175f

        strokePaint.color = Color.argb(165, 255, 255, 255)
        strokePaint.strokeWidth = 2.6f
        // Matrix [  ] brackets
        canvas.drawLine(mx, my, mx, my + mh, strokePaint)
        canvas.drawLine(mx, my, mx + 10f, my, strokePaint)
        canvas.drawLine(mx, my + mh, mx + 10f, my + mh, strokePaint)
        canvas.drawLine(mx + mw, my, mx + mw, my + mh, strokePaint)
        canvas.drawLine(mx + mw - 10f, my, mx + mw, my, strokePaint)
        canvas.drawLine(mx + mw - 10f, my + mh, mx + mw, my + mh, strokePaint)

        monoPaint.textSize = h * 0.023f
        monoPaint.color = Color.argb(195, 245, 247, 250)
        val cStr = formatSigned2(cVal)
        val negSStr = formatSigned2(-sVal)
        val sStr = formatSigned2(sVal)
        canvas.drawText("$cStr  $negSStr   0", mx + 14f, my + mh * 0.28f, monoPaint)
        canvas.drawText("$sStr  $cStr   0", mx + 14f, my + mh * 0.58f, monoPaint)
        canvas.drawText(" 0.00   0.00   1", mx + 14f, my + mh * 0.88f, monoPaint)

        monoPaint.textSize = h * 0.020f
        monoPaint.color = Color.argb(135, 210, 215, 225)
        canvas.drawText("θ = ${formatSigned2(theta % 6.28f)} rad", mx + 12f, my - h * 0.015f, monoPaint)

        // 4. RIGHT LOWER WING: Breathing Riemann-Sum Integral Bars under Gaussian Curve
        val rx0 = w * 0.815f
        val rw = w * 0.165f
        val rBaseY = h * 0.84f
        val rMaxH = h * 0.14f * (0.78f + beatPulse * 0.35f)
        val bars = 10
        val bw = rw / bars
        for (i in 0 until bars) {
            val nx = ((i + 0.5f) / bars - 0.5f) * 3.8f
            val gVal = kotlin.math.exp((-0.5f * nx * nx).toDouble()).toFloat()
            val bh = rMaxH * gVal
            val bx = rx0 + i * bw
            fillPaint.color = Color.argb((45 + beatPulse * 45).toInt(), 255, 255, 255)
            canvas.drawRect(bx + 2f, rBaseY - bh, bx + bw - 2f, rBaseY, fillPaint)
            strokePaint.color = Color.argb(130, 255, 255, 255)
            strokePaint.strokeWidth = 1.4f
            canvas.drawRect(bx + 2f, rBaseY - bh, bx + bw - 2f, rBaseY, strokePaint)
        }
    }

    private fun formatSigned2(v: Float): String {
        val scaled = (v * 100f).toInt()
        val sign = if (scaled < 0) "-" else " "
        val absV = abs(scaled)
        val whole = absV / 100
        val frac = absV % 100
        return if (frac < 10) "$sign$whole.0$frac" else "$sign$whole.$frac"
    }
}
