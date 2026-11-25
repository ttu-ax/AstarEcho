package com.astraecho.rhythm.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.astraecho.rhythm.model.Judgment
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.model.ParticleStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zero-GC pooled particle & shockwave VFX system with 4 distinct artistic styles:
 * - STAR_DIAMONDS (Astra Cyber)
 * - ARCADE_RIPPLES (Classic Master)
 * - SAKURA_PETALS (Sakura Pulse - rotating drifting petals!)
 * - CYBER_SPARKS (Crimson Overdrive - sharp laser streaks + diamond shock frames!)
 */
class ParticleSystem(maxParticles: Int = 300, maxRings: Int = 36) {

    private class Particle {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 8f
        var age = 0f
        var life = 0.45f
        var color = Color.CYAN
        var angle = 0f
        var spin = 0f
        var style = ParticleStyle.STAR_DIAMONDS
        var isStreak = false
        var glyph = "∫"
    }

    private class ShockRing {
        var active = false
        var x = 0f
        var y = 0f
        var startRadius = 12f
        var maxRadius = 110f
        var age = 0f
        var life = 0.36f
        var color = Color.CYAN
        var strokeWidth = 8f
        var isDiamondFrame = false
        var isMathCrosshair = false
    }

    private val mathGlyphs = arrayOf("∫", "∑", "π", "∞", "∂", "√", "Δ", "λ", "∇", "θ", "≡")
    private val particles = Array(maxParticles) { Particle() }
    private val rings = Array(maxRings) { ShockRing() }
    private var nextParticleIdx = 0
    private var nextRingIdx = 0
    private val rng = Random(2147)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val shapePath = Path()

    fun clear() {
        for (p in particles) p.active = false
        for (r in rings) r.active = false
    }

    fun emitTapBurst(x: Float, y: Float, laneWidth: Float, skin: NoteSkin, judgment: Judgment) {
        val mainColor = if (skin.particleStyle == ParticleStyle.MATH_SYMBOLS) {
            if (judgment == Judgment.MISS) 0xFF707580.toInt() else 0xFFFFFFFF.toInt()
        } else {
            when (judgment) {
                Judgment.PERFECT -> skin.particlePrimary
                Judgment.GREAT -> skin.tapMain
                Judgment.GOOD -> skin.holdMain
                Judgment.MISS -> 0xFFFF4D6D.toInt()
            }
        }
        val isDiamond = skin.particleStyle == ParticleStyle.CYBER_SPARKS
        val isMath = skin.particleStyle == ParticleStyle.MATH_SYMBOLS
        val count = if (isMath) (if (judgment == Judgment.PERFECT) 16 else 10) else (if (judgment == Judgment.PERFECT) 24 else 16)

        spawnRing(x, y, laneWidth * 0.16f, laneWidth * 0.96f, 0.34f, mainColor, if (isMath) 3.5f else 9f, isDiamond, isMath)
        if (judgment == Judgment.PERFECT) {
            spawnRing(x, y, laneWidth * 0.08f, laneWidth * 1.26f, 0.44f, Color.WHITE, if (isMath) 2.2f else 5.5f, isDiamond, false)
            if (skin.particleStyle == ParticleStyle.ARCADE_RIPPLES) {
                spawnRing(x, y, laneWidth * 0.24f, laneWidth * 1.45f, 0.50f, skin.particleSecondary, 4.5f, false, false)
            }
        }

        for (i in 0 until count) {
            val angle = (-PI * 0.96 + rng.nextDouble() * PI * 0.92)
            val speedScale = when (skin.particleStyle) {
                ParticleStyle.SAKURA_PETALS -> 0.72f
                ParticleStyle.MATH_SYMBOLS -> 0.85f
                ParticleStyle.CYBER_SPARKS -> 1.35f
                else -> 1.0f
            }
            val speed = ((laneWidth * 1.5f) + rng.nextFloat() * (laneWidth * 3.6f)) * speedScale
            spawnParticle(
                x = x + (rng.nextFloat() - 0.5f) * laneWidth * 0.45f,
                y = y + (rng.nextFloat() - 0.5f) * 12f,
                vx = (cos(angle) * speed).toFloat(),
                vy = (sin(angle) * speed).toFloat(),
                size = when (skin.particleStyle) {
                    ParticleStyle.SAKURA_PETALS -> 8f + rng.nextFloat() * 9f
                    ParticleStyle.MATH_SYMBOLS -> 18f + rng.nextFloat() * 14f
                    else -> 6f + rng.nextFloat() * 8f
                },
                life = when (skin.particleStyle) {
                    ParticleStyle.SAKURA_PETALS, ParticleStyle.MATH_SYMBOLS -> 0.48f + rng.nextFloat() * 0.34f
                    else -> 0.30f + rng.nextFloat() * 0.26f
                },
                color = when {
                    i % 4 == 0 -> Color.WHITE
                    i % 3 == 0 -> skin.particleSecondary
                    else -> mainColor
                },
                style = skin.particleStyle,
                isStreak = (skin.particleStyle == ParticleStyle.CYBER_SPARKS) || (i % 2 == 0 && skin.particleStyle == ParticleStyle.STAR_DIAMONDS)
            )
        }
    }

    fun emitHoldSparks(x: Float, y: Float, laneWidth: Float, skin: NoteSkin, isHoldSlide: Boolean) {
        val color = if (isHoldSlide) skin.slideMain else skin.holdMain
        val isDiamond = skin.particleStyle == ParticleStyle.CYBER_SPARKS
        val isMath = skin.particleStyle == ParticleStyle.MATH_SYMBOLS
        spawnRing(x, y, laneWidth * 0.14f, laneWidth * 0.68f, 0.24f, color, if (isMath) 2.5f else 6f, isDiamond, isMath)
        for (i in 0 until if (isMath) 5 else 7) {
            val vx = (rng.nextFloat() - 0.5f) * laneWidth * 2.1f
            val vy = -(laneWidth * 1.3f + rng.nextFloat() * laneWidth * 2.3f)
            spawnParticle(
                x = x + (rng.nextFloat() - 0.5f) * laneWidth * 0.48f,
                y = y,
                vx = vx,
                vy = vy,
                size = if (isMath) 16f + rng.nextFloat() * 10f else 5.5f + rng.nextFloat() * 7f,
                life = 0.28f + rng.nextFloat() * 0.24f,
                color = if (i % 3 == 0) Color.WHITE else color,
                style = skin.particleStyle,
                isStreak = skin.particleStyle == ParticleStyle.CYBER_SPARKS
            )
        }
    }

    fun emitSlideComet(startX: Float, endX: Float, y: Float, laneWidth: Float, skin: NoteSkin) {
        val dir = if (endX >= startX) 1f else -1f
        val isDiamond = skin.particleStyle == ParticleStyle.CYBER_SPARKS
        val isMath = skin.particleStyle == ParticleStyle.MATH_SYMBOLS
        spawnRing(startX, y, laneWidth * 0.16f, laneWidth * 0.85f, 0.30f, skin.slideMain, if (isMath) 3f else 8f, isDiamond, isMath)
        spawnRing(endX, y, laneWidth * 0.22f, laneWidth * 1.22f, 0.38f, skin.slideHighlight, if (isMath) 3.5f else 10f, isDiamond, isMath)

        val steps = if (isMath) 16 else 26
        for (i in 0 until steps) {
            val frac = i.toFloat() / (steps - 1)
            val px = startX + (endX - startX) * frac
            spawnParticle(
                x = px,
                y = y + (rng.nextFloat() - 0.5f) * 18f,
                vx = dir * (laneWidth * 2.1f + rng.nextFloat() * laneWidth * 2.9f),
                vy = -(laneWidth * 0.6f + rng.nextFloat() * laneWidth * 2.2f),
                size = if (isMath) 17f + rng.nextFloat() * 11f else 7f + rng.nextFloat() * 8f,
                life = 0.34f + rng.nextFloat() * 0.26f,
                color = if (i % 3 == 0) Color.WHITE else skin.slideMain,
                style = skin.particleStyle,
                isStreak = skin.particleStyle != ParticleStyle.SAKURA_PETALS && !isMath
            )
        }
    }

    private fun spawnParticle(
        x: Float, y: Float, vx: Float, vy: Float,
        size: Float, life: Float, color: Int, style: ParticleStyle, isStreak: Boolean
    ) {
        val p = particles[nextParticleIdx]
        nextParticleIdx = (nextParticleIdx + 1) % particles.size
        p.active = true
        p.x = x
        p.y = y
        p.vx = vx
        p.vy = vy
        p.size = size
        p.age = 0f
        p.life = life
        p.color = color
        p.angle = rng.nextFloat() * 360f
        p.spin = (rng.nextFloat() - 0.5f) * 420f
        p.style = style
        p.isStreak = isStreak
        p.glyph = mathGlyphs[rng.nextInt(mathGlyphs.size)]
    }

    private fun spawnRing(
        x: Float, y: Float, startR: Float, maxR: Float,
        life: Float, color: Int, strokeW: Float, isDiamondFrame: Boolean, isMathCrosshair: Boolean
    ) {
        val r = rings[nextRingIdx]
        nextRingIdx = (nextRingIdx + 1) % rings.size
        r.active = true
        r.x = x
        r.y = y
        r.startRadius = startR
        r.maxRadius = maxR
        r.age = 0f
        r.life = life
        r.color = color
        r.strokeWidth = strokeW
        r.isDiamondFrame = isDiamondFrame
        r.isMathCrosshair = isMathCrosshair
    }

    fun update(dtSec: Float) {
        val dt = dtSec.coerceIn(0.001f, 0.04f)
        for (p in particles) {
            if (!p.active) continue
            p.age += dt
            if (p.age >= p.life) {
                p.active = false
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.angle += p.spin * dt
            if (p.style == ParticleStyle.SAKURA_PETALS) {
                p.vx *= (1f - 1.8f * dt)
                p.vy += 260f * dt // gentle floating cherry blossom fall
            } else {
                p.vx *= (1f - 2.6f * dt)
                p.vy += 680f * dt
            }
        }
        for (r in rings) {
            if (!r.active) continue
            r.age += dt
            if (r.age >= r.life) r.active = false
        }
    }

    fun draw(canvas: Canvas) {
        for (r in rings) {
            if (!r.active) continue
            val t = (r.age / r.life).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            val radius = r.startRadius + (r.maxRadius - r.startRadius) * eased
            val alpha = ((1f - t) * 235).toInt().coerceIn(0, 255)
            strokePaint.color = Color.argb(alpha, Color.red(r.color), Color.green(r.color), Color.blue(r.color))
            strokePaint.strokeWidth = r.strokeWidth * (1f - t * 0.65f)

            if (r.isDiamondFrame) {
                shapePath.reset()
                shapePath.moveTo(r.x, r.y - radius * 0.85f)
                shapePath.lineTo(r.x + radius, r.y)
                shapePath.lineTo(r.x, r.y + radius * 0.85f)
                shapePath.lineTo(r.x - radius, r.y)
                shapePath.close()
                canvas.drawPath(shapePath, strokePaint)
            } else {
                canvas.drawCircle(r.x, r.y, radius, strokePaint)
                if (r.isMathCrosshair) {
                    val cr = radius * 1.18f
                    canvas.drawLine(r.x - cr, r.y, r.x + cr, r.y, strokePaint)
                    canvas.drawLine(r.x, r.y - cr * 0.7f, r.x, r.y + cr * 0.7f, strokePaint)
                }
            }
        }

        for (p in particles) {
            if (!p.active) continue
            val t = (p.age / p.life).coerceIn(0f, 1f)
            val alpha = ((1f - t * t) * 255).toInt().coerceIn(0, 255)
            val s = p.size * (1f - t * 0.45f)
            val r = Color.red(p.color)
            val g = Color.green(p.color)
            val b = Color.blue(p.color)

            when {
                p.style == ParticleStyle.MATH_SYMBOLS -> {
                    glyphPaint.color = Color.argb(alpha, r, g, b)
                    glyphPaint.textSize = s
                    canvas.drawText(p.glyph, p.x, p.y, glyphPaint)
                }
                p.style == ParticleStyle.SAKURA_PETALS -> {
                    fillPaint.color = Color.argb(alpha, r, g, b)
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.angle)
                    shapePath.reset()
                    shapePath.moveTo(0f, -s)
                    shapePath.quadTo(s * 0.85f, -s * 0.15f, 0f, s)
                    shapePath.quadTo(-s * 0.85f, -s * 0.15f, 0f, -s)
                    shapePath.close()
                    canvas.drawPath(shapePath, fillPaint)
                    canvas.restore()
                }
                p.isStreak -> {
                    strokePaint.color = Color.argb(alpha, r, g, b)
                    strokePaint.strokeWidth = s * 0.55f
                    canvas.drawLine(p.x, p.y, p.x - p.vx * 0.032f, p.y - p.vy * 0.032f, strokePaint)
                }
                p.style == ParticleStyle.ARCADE_RIPPLES -> {
                    fillPaint.color = Color.argb(alpha, r, g, b)
                    canvas.drawCircle(p.x, p.y, s * 0.68f, fillPaint)
                }
                else -> {
                    fillPaint.color = Color.argb(alpha, r, g, b)
                    shapePath.reset()
                    shapePath.moveTo(p.x, p.y - s)
                    shapePath.lineTo(p.x + s * 0.72f, p.y)
                    shapePath.lineTo(p.x, p.y + s)
                    shapePath.lineTo(p.x - s * 0.72f, p.y)
                    shapePath.close()
                    canvas.drawPath(shapePath, fillPaint)
                }
            }
        }
    }
}
