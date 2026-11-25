package com.astraecho.rhythm.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.astraecho.rhythm.R
import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartNote
import com.astraecho.rhythm.model.FlickDirection
import com.astraecho.rhythm.model.GameResult
import com.astraecho.rhythm.model.NoteGeometryStyle
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.model.NoteType
import com.astraecho.rhythm.model.PlayerSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-smooth VSync-locked (60/90/120/144Hz) hardware-accelerated SurfaceView rhythm engine!
 * - Uses Choreographer + lockHardwareCanvas() on GPU (zero CPU software blits, zero Thread.join deadlocks!)
 * - Zero per-frame Shader/Paint allocations for rock-solid frame pacing on Snapdragon 8 Elite & tablets
 * - Smooth nanoTime PLL audio clock (zero visual teleporting)
 * - Instant bug-free Pause / Resume / Restart
 * - Cross-lane swipe-sweep triggering (sweep across lanes 1-2-3 in one gesture!)
 * - 4 deeply distinct visual themes (unique backgrounds, note geometry, track styling, particles, judgments)
 */
class GameSurfaceView(
    context: Context,
    private val audioPath: String,
    private val chart: BeatmapChart,
    initialSettings: PlayerSettings,
    private val onHudUpdate: (HudSnapshot) -> Unit,
    private val onGameFinished: (GameResult) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private var settings: PlayerSettings = initialSettings
    private val engine = GameEngine(chart)
    private val particles = ParticleSystem()
    private val skinAnimator = SkinStageAnimator()
    private val visualEventBuffer = ArrayList<VisualEvent>(32)
    private var animTimeSec = 0f

    private var surfaceReady = false
    private var paused = false
    private var resumeCountdownRemainingMs = 0.0
    private val resumeCountdownTotalMs = 1950.0 // 3 -> 2 -> 1 (~650ms each)
    private var finished = false
    private var released = false

    private var mediaPlayer: MediaPlayer? = null
    private val choreographer = Choreographer.getInstance()

    // High-precision monotonic clock in milliseconds (Double precision via System.nanoTime)
    private val leadInMs = 1800.0
    private var songClockBaseMs = 0.0
    private var pausedSongTimeMs = -leadInMs
    private var audioStarted = false
    private var lastFrameNanos = 0L
    private var lastHudDispatchMs = 0L

    private data class PointerState(
        val pointerId: Int,
        var lane: Int,
        var anchorX: Float,
        var slideConsumed: Boolean = false
    )

    private val activePointers = HashMap<Int, PointerState>()
    private val livePointerIdScratch = HashSet<Int>(16)
    private val stalePointerScratch = ArrayList<Int>(8)
    private val pressedLanes = BooleanArray(chart.laneCount)
    private val laneFlashAlpha = FloatArray(chart.laneCount)
    private val laneMathSymbols = arrayOf("∑", "∫", "π", "∂", "∞", "λ")
    private val mathNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    // Lazy-cached per-skin stage backgrounds
    private val skinBgCache = HashMap<NoteSkin, Bitmap?>()

    // Cached Shaders to guarantee ZERO native Skia Shader allocations per frame!
    private var cachedW = 0f
    private var cachedH = 0f
    private var cachedTilt = -1f
    private var cachedSkin: NoteSkin? = null
    private var cachedProj: TrackProjection? = null
    private var trackBgShader: LinearGradient? = null
    private var laneBeamShader: LinearGradient? = null
    private var keyIdleShader: LinearGradient? = null
    private var keyPressedShader: LinearGradient? = null

    // Reusable Paints & Geometry (zero allocation in draw loop)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val railGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val comboTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val judgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val path = Path()
    private val quadPath = Path()
    private val rectF = RectF()
    private val srcRect = Rect()

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun nowMs(): Double = System.nanoTime() / 1_000_000.0

    private fun getStageBitmap(skin: NoteSkin): Bitmap? {
        return skinBgCache.getOrPut(skin) {
            val resId = when (skin) {
                NoteSkin.ASTRA_CYBER -> R.drawable.bg_stage
                NoteSkin.CLASSIC_MASTER -> R.drawable.bg_skin_classic
                NoteSkin.SAKURA_PULSE -> R.drawable.bg_skin_sakura
                NoteSkin.CRIMSON_OVERDRIVE -> R.drawable.bg_skin_crimson
                NoteSkin.EULER_CHALK -> R.drawable.bg_skin_math
            }
            runCatching { BitmapFactory.decodeResource(resources, resId) }.getOrNull()
        }
    }

    fun updateSettings(newSettings: PlayerSettings) {
        settings = newSettings
    }

    /**
     * Bug-free Pause / Resume: captures songTime BEFORE flipping [paused].
     * When resuming via [resumeWithCountdown], runs a crisp 3 -> 2 -> 1 countdown before restarting playback.
     */
    fun setPaused(shouldPause: Boolean) {
        if (finished || released) return
        if (shouldPause) {
            if (!paused) {
                pausedSongTimeMs = currentSongTimeDoubleMs()
                paused = true
            }
            resumeCountdownRemainingMs = 0.0
            activePointers.clear()
            recomputePressedLanes()
            runCatching {
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
            }
        } else {
            resumeNow()
        }
    }

    fun resumeWithCountdown() {
        if (!paused || finished || released) return
        resumeCountdownRemainingMs = resumeCountdownTotalMs
        lastFrameNanos = System.nanoTime()
    }

    private fun resumeNow() {
        val now = nowMs()
        songClockBaseMs = now - pausedSongTimeMs
        paused = false
        resumeCountdownRemainingMs = 0.0
        lastFrameNanos = System.nanoTime()
        if (pausedSongTimeMs >= 0.0) {
            audioStarted = true
            runCatching {
                val mp = mediaPlayer
                if (mp != null) {
                    val targetMs = pausedSongTimeMs.toInt().coerceAtLeast(0)
                    if (abs(mp.currentPosition - targetMs) > 60) {
                        mp.seekTo(targetMs)
                    }
                    if (!mp.isPlaying) mp.start()
                }
            }
        }
    }

    /**
     * Instant, deadlock-free Restart (reuses existing SurfaceView & MediaPlayer!).
     */
    fun restartGame() {
        if (released) return
        paused = false
        resumeCountdownRemainingMs = 0.0
        finished = false
        audioStarted = false
        activePointers.clear()
        for (i in pressedLanes.indices) pressedLanes[i] = false
        for (i in laneFlashAlpha.indices) laneFlashAlpha[i] = 0f

        engine.reset()
        particles.clear()
        visualEventBuffer.clear()

        runCatching {
            val mp = mediaPlayer
            if (mp != null) {
                if (mp.isPlaying) mp.pause()
                mp.seekTo(0)
            } else {
                prepareAudio()
            }
        }

        pausedSongTimeMs = -leadInMs
        songClockBaseMs = nowMs() + leadInMs
        lastFrameNanos = System.nanoTime()
        onHudUpdate(HudSnapshot())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        released = false
        surfaceReady = true
        prepareAudio()
        songClockBaseMs = nowMs() - pausedSongTimeMs
        lastFrameNanos = System.nanoTime()
        choreographer.removeFrameCallback(this)
        choreographer.postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cachedW = 0f // force shader cache rebuild for new dimensions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && width > 0 && height > 0) {
            // Protect bottom keyboard touch zone from Android system edge/gesture interception
            systemGestureExclusionRects = listOf(Rect(0, (height * 0.55f).toInt(), width, height))
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        choreographer.removeFrameCallback(this)
        releaseAudioOnly()
    }

    fun stopAndRelease() {
        released = true
        surfaceReady = false
        choreographer.removeFrameCallback(this)
        releaseAudioOnly()
    }

    private fun releaseAudioOnly() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null
    }

    private fun prepareAudio() {
        releaseAudioOnly()
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioPath)
                prepare()
            }
        }
    }

    private fun currentSongTimeDoubleMs(): Double {
        if (paused) return pausedSongTimeMs
        val now = nowMs()
        var t = now - songClockBaseMs
        val mp = mediaPlayer
        if (audioStarted && mp != null && runCatching { mp.isPlaying }.getOrDefault(false)) {
            val audioPos = mp.currentPosition.toDouble()
            val drift = audioPos - t
            // Smooth PLL correction: nudge at most 1.8ms per frame so notes NEVER visibly jump!
            if (abs(drift) > 55.0) {
                val nudge = drift.coerceIn(-1.8, 1.8)
                songClockBaseMs -= nudge
                t = now - songClockBaseMs
            }
        }
        return t + settings.audioOffsetMs
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!surfaceReady || released) return

        val dtSec = if (lastFrameNanos > 0L) {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.04f)
        } else 0.016f
        lastFrameNanos = frameTimeNanos
        animTimeSec += dtSec

        val wallNowMs = SystemClock.elapsedRealtime()

        // Tick resume countdown (3 -> 2 -> 1) while keeping track frozen at exact paused position!
        if (paused && resumeCountdownRemainingMs > 0.0) {
            resumeCountdownRemainingMs -= dtSec * 1000.0
            if (resumeCountdownRemainingMs <= 0.0) {
                resumeNow()
            }
        }

        val songTimeDouble = currentSongTimeDoubleMs()
        val songTimeMs = songTimeDouble.toLong()

        if (!paused && !finished) {
            if (!audioStarted && songTimeDouble >= 0.0) {
                audioStarted = true
                songClockBaseMs = nowMs()
                runCatching { mediaPlayer?.start() }
            }

            engine.update(songTimeMs, wallNowMs)
            engine.drainVisualEvents(visualEventBuffer)

            if (wallNowMs - lastHudDispatchMs >= 65L) {
                lastHudDispatchMs = wallNowMs
                onHudUpdate(engine.snapshot(songTimeMs))
            }

            if (engine.isFinished(songTimeMs)) {
                finished = true
                onGameFinished(engine.buildResult())
            }
        }

        for (i in laneFlashAlpha.indices) {
            if (laneFlashAlpha[i] > 0f) {
                laneFlashAlpha[i] = max(0f, laneFlashAlpha[i] - dtSec * 4.8f)
            }
        }

        val canvas = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                holder.lockHardwareCanvas()
            } else {
                holder.lockCanvas()
            }
        } catch (_: Throwable) {
            null
        }

        if (canvas != null) {
            try {
                val proj = ensureProjectionAndShaders(canvas.width.toFloat(), canvas.height.toFloat(), settings.skin)
                if (visualEventBuffer.isNotEmpty()) {
                    processVisualEvents(proj, settings.skin)
                    visualEventBuffer.clear()
                }
                particles.update(dtSec)
                drawFrame(canvas, proj, songTimeDouble, wallNowMs)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Throwable) {
                }
            }
        }

        if (surfaceReady && !released) {
            choreographer.postFrameCallback(this)
        }
    }

    private fun ensureProjectionAndShaders(w: Float, h: Float, skin: NoteSkin): TrackProjection {
        val existing = cachedProj
        if (existing != null && cachedW == w && cachedH == h && cachedTilt == settings.trackTiltDegrees && cachedSkin == skin) {
            return existing
        }
        val proj = TrackProjection(w, h, chart.laneCount, settings.trackTiltDegrees)
        cachedW = w
        cachedH = h
        cachedTilt = settings.trackTiltDegrees
        cachedSkin = skin
        cachedProj = proj

        trackBgShader = LinearGradient(
            0f, proj.topY, 0f, proj.judgeY,
            skin.trackTintTop,
            skin.trackTintBottom,
            Shader.TileMode.CLAMP
        )
        val kg = skin.keyGlowColor
        laneBeamShader = LinearGradient(
            0f, proj.topY, 0f, proj.judgeY,
            Color.argb(12, Color.red(kg), Color.green(kg), Color.blue(kg)),
            Color.argb(170, Color.red(kg), Color.green(kg), Color.blue(kg)),
            Shader.TileMode.CLAMP
        )
        keyIdleShader = LinearGradient(
            0f, proj.judgeY, 0f, proj.bottomY,
            Color.argb(215, 16, 26, 52),
            Color.argb(245, 7, 11, 24),
            Shader.TileMode.CLAMP
        )
        keyPressedShader = LinearGradient(
            0f, proj.judgeY, 0f, proj.bottomY,
            Color.argb(225, Color.red(kg), Color.green(kg), Color.blue(kg)),
            Color.argb(235, 10, 16, 36),
            Shader.TileMode.CLAMP
        )
        return proj
    }

    private fun processVisualEvents(proj: TrackProjection, skin: NoteSkin) {
        for (ev in visualEventBuffer) {
            when (ev) {
                is VisualEvent.TapHit -> {
                    val cx = proj.xAt(ev.lane + 0.5f, 1f)
                    if (ev.lane in laneFlashAlpha.indices) laneFlashAlpha[ev.lane] = 1f
                    particles.emitTapBurst(cx, proj.judgeY, proj.laneWidthAtJudge, skin, ev.judgment)
                }
                is VisualEvent.HoldTick -> {
                    val cx = proj.xAt(ev.lane + 0.5f, 1f)
                    val idx = kotlin.math.round(ev.lane).toInt()
                    if (idx in laneFlashAlpha.indices) laneFlashAlpha[idx] = max(laneFlashAlpha[idx], 0.78f)
                    particles.emitHoldSparks(cx, proj.judgeY, proj.laneWidthAtJudge, skin, ev.isHoldSlide)
                }
                is VisualEvent.SlideBurst -> {
                    val sx = proj.xAt(ev.fromLane + 0.5f, 1f)
                    val ex = proj.xAt(ev.toLane + 0.5f, 1f)
                    val lo = min(ev.fromLane, ev.toLane)
                    val hi = max(ev.fromLane, ev.toLane)
                    for (l in lo..hi) {
                        if (l in laneFlashAlpha.indices) laneFlashAlpha[l] = 1f
                    }
                    particles.emitSlideComet(sx, ex, proj.judgeY, proj.laneWidthAtJudge, skin)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (paused || finished || width <= 0 || height <= 0) return true

        val proj = ensureProjectionAndShaders(width.toFloat(), height.toFloat(), settings.skin)
        val songTimeMs = currentSongTimeDoubleMs().toLong()
        val wallNow = SystemClock.elapsedRealtime()
        val slideThresholdPx = max(24f, proj.laneWidthAtJudge * 0.26f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // Handle primary newly-down pointer AND any coalesced newly-arrived pointers (crucial for 4-finger chords!)
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid !in activePointers) {
                        val x = event.getX(i)
                        val y = event.getY(i)
                        val lane = proj.laneIndexForTouch(x, y)
                        activePointers[pid] = PointerState(pid, lane, x, false)
                        if (lane in laneFlashAlpha.indices) laneFlashAlpha[lane] = max(laneFlashAlpha[lane], 0.55f)
                        engine.onLanePress(lane, pid, songTimeMs, wallNow)
                    }
                }
                recomputePressedLanes()
            }

            MotionEvent.ACTION_MOVE -> {
                // Reconcile activePointers against live pointers in event so NO lane can ever get stuck!
                livePointerIdScratch.clear()
                for (i in 0 until event.pointerCount) {
                    livePointerIdScratch.add(event.getPointerId(i))
                }
                stalePointerScratch.clear()
                for (pid in activePointers.keys) {
                    if (pid !in livePointerIdScratch) stalePointerScratch.add(pid)
                }
                for (stalePid in stalePointerScratch) {
                    val staleState = activePointers.remove(stalePid)
                    val replacement = findAnotherPointerOnLane(staleState?.lane ?: -1, excludePid = stalePid)
                    engine.onPointerRelease(stalePid, songTimeMs, wallNow, replacement)
                }

                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val existing = activePointers[pid]
                    if (existing == null) {
                        // Brand-new pointer arriving in ACTION_MOVE (e.g. 4/5/6-finger coalesced touch or post-cancel):
                        // MUST trigger onLanePress so notes are NEVER missed!
                        val lane = proj.laneIndexForTouch(x, y)
                        activePointers[pid] = PointerState(pid, lane, x, false)
                        if (lane in laneFlashAlpha.indices) laneFlashAlpha[lane] = max(laneFlashAlpha[lane], 0.55f)
                        engine.onLanePress(lane, pid, songTimeMs, wallNow)
                        continue
                    }

                    val ps = existing
                    val prevLane = ps.lane
                    val newLane = proj.laneIndexForTouch(x, y)
                    val dx = x - ps.anchorX

                    if (!ps.slideConsumed && abs(dx) >= slideThresholdPx) {
                        val dir = if (dx > 0f) FlickDirection.RIGHT else FlickDirection.LEFT
                        val hit = engine.onLaneSlide(prevLane, dir, songTimeMs, wallNow)
                            ?: engine.onLaneSlide(newLane, dir, songTimeMs, wallNow)
                        if (hit != null) {
                            ps.slideConsumed = true
                            ps.anchorX = x
                        }
                    }

                    if (newLane != prevLane) {
                        val step = if (newLane > prevLane) 1 else -1
                        var cur = prevLane + step
                        while (true) {
                            engine.onPointerMoveLane(pid, cur)
                            if (cur in laneFlashAlpha.indices) laneFlashAlpha[cur] = max(laneFlashAlpha[cur], 0.65f)
                            engine.onLaneSweepEnter(cur, pid, songTimeMs, wallNow)
                            if (cur == newLane) break
                            cur += step
                        }
                        ps.lane = newLane
                        if (ps.slideConsumed) {
                            ps.slideConsumed = false
                            ps.anchorX = x
                        }
                    }
                }
                recomputePressedLanes()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val removed = activePointers.remove(pid)
                val replacement = findAnotherPointerOnLane(removed?.lane ?: -1, excludePid = pid)
                recomputePressedLanes()
                engine.onPointerRelease(pid, songTimeMs, wallNow, replacement)
            }

            MotionEvent.ACTION_UP -> {
                // Last finger lifted: cleanly release ALL remaining pointers so nothing ever sticks!
                stalePointerScratch.clear()
                stalePointerScratch.addAll(activePointers.keys)
                activePointers.clear()
                recomputePressedLanes()
                for (pid in stalePointerScratch) {
                    engine.onPointerRelease(pid, songTimeMs, wallNow, -1)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // System gesture (e.g. 3-finger screenshot) cancelled touches:
                // clear all stuck lanes immediately AND preserve active holds gracefully!
                activePointers.clear()
                recomputePressedLanes()
                engine.onSystemTouchCancel()
            }
        }
        return true
    }

    private fun findAnotherPointerOnLane(lane: Int, excludePid: Int): Int {
        if (lane < 0) return -1
        for (ps in activePointers.values) {
            if (ps.pointerId != excludePid && ps.lane == lane) return ps.pointerId
        }
        return -1
    }

    private fun recomputePressedLanes() {
        for (i in pressedLanes.indices) pressedLanes[i] = false
        for (ps in activePointers.values) {
            if (ps.lane in pressedLanes.indices) pressedLanes[ps.lane] = true
        }
    }

    private fun drawFrame(canvas: Canvas, proj: TrackProjection, songTimeDouble: Double, wallNowMs: Long) {
        val skin = settings.skin
        val songTimeMs = songTimeDouble.toLong()
        val beatMs = (60_000.0 / chart.bpm.coerceIn(72, 190))
        val beatPhase = ((max(0.0, songTimeDouble) / beatMs) % 1.0).toFloat()

        // 1. Per-Skin Bespoke Stage Background + Live Animated Wing Elements
        val bg = getStageBitmap(skin)
        if (bg != null) {
            srcRect.set(0, 0, bg.width, bg.height)
            rectF.set(0f, 0f, proj.width, proj.height)
            canvas.drawBitmap(bg, srcRect, rectF, bitmapPaint)
        } else {
            canvas.drawColor(Color.rgb(5, 8, 20))
        }
        skinAnimator.drawBackgroundEffects(canvas, proj, skin, animTimeSec, beatPhase)

        // 2. Seamless Perspective Track & 3D Keyboard + Animated Track & Key Motion
        drawSeamlessTrackAndKeyboard(canvas, proj, skin, beatPhase)

        // 3. Falling Notes
        val approachMs = settings.approachTimeMs.toDouble()

        // Pass A: Hold ribbons & Hold+Slide rails (MISSED/early-released holds stay visible & turn translucent gray!)
        for (rn in engine.runtimeNotes) {
            if (rn.state == NoteRuntimeState.COMPLETED) continue
            val isDropped = rn.state == NoteRuntimeState.MISSED
            when (rn.note.type) {
                NoteType.HOLD -> {
                    val headT = when {
                        rn.state == NoteRuntimeState.HOLDING -> max(rn.note.timeMs.toDouble(), songTimeDouble)
                        isDropped && rn.droppedAtMs > rn.note.timeMs -> rn.droppedAtMs.toDouble()
                        else -> rn.note.timeMs.toDouble()
                    }
                    val headP = (1.0 - (headT - songTimeDouble) / approachMs).toFloat()
                    val tailP = (1.0 - (rn.note.endTimeMs - songTimeDouble) / approachMs).toFloat()
                    if (headP >= -0.05f && tailP <= 1.25f) {
                        drawHoldRibbon(
                            canvas, proj, rn.note.lane,
                            max(0f, tailP), min(1.22f, headP),
                            skin,
                            isHolding = rn.state == NoteRuntimeState.HOLDING,
                            isDropped = isDropped
                        )
                    }
                }
                NoteType.HOLD_SLIDE -> {
                    val startP = (1.0 - (rn.note.timeMs - songTimeDouble) / approachMs).toFloat()
                    val endP = (1.0 - (rn.note.endTimeMs - songTimeDouble) / approachMs).toFloat()
                    if (startP >= -0.05f && endP <= 1.25f) {
                        drawHoldSlideRail(canvas, proj, rn, songTimeDouble, approachMs, skin, isDropped = isDropped)
                    }
                }
                else -> Unit
            }
        }

        // Pass B: Cross-lane SLIDE arrows
        for (rn in engine.runtimeNotes) {
            if (rn.state == NoteRuntimeState.COMPLETED || rn.state == NoteRuntimeState.MISSED) continue
            if (rn.note.type != NoteType.SLIDE) continue
            val p = (1.0 - (rn.note.timeMs - songTimeDouble) / approachMs).toFloat()
            if (p < 0f || p > 1.15f) continue
            drawCrossLaneSlideArrow(canvas, proj, rn.note.lane, rn.note.slideTargetLane, p, skin)
        }

        // Pass C: Skin-Specific Note Head Geometries (keep grayed-out head visible for missed holds as they pass!)
        for (rn in engine.runtimeNotes) {
            if (rn.state == NoteRuntimeState.COMPLETED) continue
            val isDropped = rn.state == NoteRuntimeState.MISSED
            if (isDropped && rn.note.type != NoteType.HOLD && rn.note.type != NoteType.HOLD_SLIDE) continue

            val isHolding = rn.state == NoteRuntimeState.HOLDING
            val headT = when {
                isHolding -> songTimeDouble
                isDropped && rn.droppedAtMs > rn.note.timeMs -> rn.droppedAtMs.toDouble()
                else -> rn.note.timeMs.toDouble()
            }
            val p = (1.0 - (headT - songTimeDouble) / approachMs).toFloat()
            if (p < 0f || p > 1.18f) continue

            val drawLaneF = if ((isHolding || isDropped) && rn.note.type == NoteType.HOLD_SLIDE) {
                engine.expectedLaneFloatAt(rn.note, headT.toLong())
            } else {
                rn.note.lane.toFloat()
            }
            drawStyledNoteHead(canvas, proj, drawLaneF, p, rn.note, skin, isHolding, isDropped)
        }

        // 4. Pooled Particle Bursts & Shockwaves
        particles.draw(canvas)

        // 5. Center Combo & Skin-Styled Judgment Text (fast 2-pass glow, zero Blur/ShadowLayer lag!)
        drawComboAndJudgment(canvas, proj, wallNowMs, skin)

        // 6. Lead-in countdown OR Pause-Resume countdown (3 -> 2 -> 1)
        if (paused && resumeCountdownRemainingMs > 0.0) {
            val sec = ((resumeCountdownRemainingMs / 650.0).toInt() + 1).coerceIn(1, 3)
            val subFrac = ((resumeCountdownRemainingMs % 650.0) / 650.0).toFloat()
            val radius = proj.height * (0.11f + (1f - subFrac) * 0.045f)

            fillPaint.shader = null
            fillPaint.color = Color.argb(120, 6, 8, 16)
            canvas.drawCircle(proj.centerX, proj.height * 0.44f, radius * 1.15f, fillPaint)

            strokePaint.color = skin.railColor
            strokePaint.strokeWidth = 6f
            canvas.drawCircle(proj.centerX, proj.height * 0.44f, radius, strokePaint)

            drawGlowText(
                canvas,
                sec.toString(),
                proj.centerX,
                proj.height * 0.495f,
                proj.height * (0.145f + subFrac * 0.03f),
                Color.WHITE,
                comboTextPaint,
                glowColor = skin.railColor
            )
        } else if (songTimeDouble < -150.0) {
            val sec = ((-songTimeMs + 850L) / 1000L).coerceIn(1L, 3L)
            drawGlowText(canvas, sec.toString(), proj.centerX, proj.height * 0.45f, proj.height * 0.16f, skin.railColor, comboTextPaint)
        }
    }

    private fun drawSeamlessTrackAndKeyboard(canvas: Canvas, proj: TrackProjection, skin: NoteSkin, beatPhase: Float) {
        val railR = Color.red(skin.railColor)
        val railG = Color.green(skin.railColor)
        val railB = Color.blue(skin.railColor)

        // Track surface trapezoid (cached shader = 0 allocations!)
        path.reset()
        path.moveTo(proj.topLeft, proj.topY)
        path.lineTo(proj.topRight, proj.topY)
        path.lineTo(proj.judgeRight, proj.judgeY)
        path.lineTo(proj.judgeLeft, proj.judgeY)
        path.close()
        fillPaint.shader = trackBgShader
        fillPaint.alpha = 255
        canvas.drawPath(path, fillPaint)

        // Live animated skin-specific track motion (beat scanlines, runner beads, scrolling ruler, laser surges!)
        skinAnimator.drawTrackMotionEffects(canvas, proj, skin, animTimeSec, beatPhase)

        // Pressed & hit-flashed lane columns (cached shader + alpha!)
        fillPaint.shader = laneBeamShader
        for (lane in 0 until chart.laneCount) {
            val intensity = max(if (pressedLanes[lane]) 0.72f else 0f, laneFlashAlpha[lane])
            if (intensity > 0.02f) {
                quadPath.reset()
                quadPath.moveTo(proj.xAt(lane.toFloat(), 0f), proj.topY)
                quadPath.lineTo(proj.xAt((lane + 1).toFloat(), 0f), proj.topY)
                quadPath.lineTo(proj.xAt((lane + 1).toFloat(), 1f), proj.judgeY)
                quadPath.lineTo(proj.xAt(lane.toFloat(), 1f), proj.judgeY)
                quadPath.close()
                fillPaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)
                canvas.drawPath(quadPath, fillPaint)
            }
        }
        fillPaint.alpha = 255

        // Seamless 3D Perspective Keyboard Keys (judgeY -> bottomY)
        for (lane in 0 until chart.laneCount) {
            val tlX = proj.xAt(lane.toFloat(), 1f)
            val trX = proj.xAt((lane + 1).toFloat(), 1f)
            val blX = proj.keyBottomXAt(lane.toFloat())
            val brX = proj.keyBottomXAt((lane + 1).toFloat())

            quadPath.reset()
            quadPath.moveTo(tlX, proj.judgeY)
            quadPath.lineTo(trX, proj.judgeY)
            quadPath.lineTo(brX, proj.bottomY)
            quadPath.lineTo(blX, proj.bottomY)
            quadPath.close()

            val isPressed = pressedLanes[lane]
            val flash = laneFlashAlpha[lane]
            fillPaint.shader = if (isPressed || flash > 0.25f) keyPressedShader else keyIdleShader
            canvas.drawPath(quadPath, fillPaint)

            // Crisp top bevel bar on judgment seam
            strokePaint.color = if (isPressed || flash > 0.2f) Color.WHITE else Color.argb(200, railR, railG, railB)
            strokePaint.strokeWidth = if (isPressed) 7f else 4f
            canvas.drawLine(tlX + 3f, proj.judgeY + 2f, trX - 3f, proj.judgeY + 2f, strokePaint)

            strokePaint.color = Color.argb(if (isPressed) 225 else 95, railR, railG, railB)
            strokePaint.strokeWidth = 2.4f
            canvas.drawPath(quadPath, strokePaint)
        }
        fillPaint.shader = null

        // Continuous Lane Dividers top -> judgeY -> bottomY
        strokePaint.color = Color.argb(95, railR, railG, railB)
        strokePaint.strokeWidth = 2.2f
        for (i in 1 until chart.laneCount) {
            val tx = proj.xAt(i.toFloat(), 0f)
            val jx = proj.xAt(i.toFloat(), 1f)
            val bx = proj.keyBottomXAt(i.toFloat())
            canvas.drawLine(tx, proj.topY, jx, proj.judgeY, strokePaint)
            canvas.drawLine(jx, proj.judgeY, bx, proj.bottomY, strokePaint)
        }

        // Continuous Outer Neon Rails
        strokePaint.color = Color.argb(85, railR, railG, railB)
        strokePaint.strokeWidth = 14f
        canvas.drawLine(proj.topLeft, proj.topY, proj.keyBottomLeft, proj.bottomY, strokePaint)
        canvas.drawLine(proj.topRight, proj.topY, proj.keyBottomRight, proj.bottomY, strokePaint)

        strokePaint.color = Color.argb(240, railR, railG, railB)
        strokePaint.strokeWidth = 4.5f
        canvas.drawLine(proj.topLeft, proj.topY, proj.keyBottomLeft, proj.bottomY, strokePaint)
        canvas.drawLine(proj.topRight, proj.topY, proj.keyBottomRight, proj.bottomY, strokePaint)

        // Seamless Judgment Seam Line
        strokePaint.color = Color.argb(110, railR, railG, railB)
        strokePaint.strokeWidth = 18f
        canvas.drawLine(proj.judgeLeft - 8f, proj.judgeY, proj.judgeRight + 8f, proj.judgeY, strokePaint)

        strokePaint.color = Color.argb(245, 242, 252, 255)
        strokePaint.strokeWidth = 4f
        canvas.drawLine(proj.judgeLeft - 4f, proj.judgeY, proj.judgeRight + 4f, proj.judgeY, strokePaint)

        // Animated skin-specific keyboard cap decorations & press pulses
        skinAnimator.drawKeyboardEffects(canvas, proj, skin, pressedLanes, laneFlashAlpha, animTimeSec, beatPhase)
    }

    private fun drawHoldRibbon(
        canvas: Canvas,
        proj: TrackProjection,
        lane: Int,
        tailP: Float,
        headP: Float,
        skin: NoteSkin,
        isHolding: Boolean,
        isDropped: Boolean = false
    ) {
        if (headP <= tailP) return
        val inset = 0.22f
        val l0 = lane + inset
        val l1 = lane + 1f - inset

        val yTail = proj.yAt(tailP)
        val yHead = proj.yAt(headP)
        quadPath.reset()
        quadPath.moveTo(proj.xAt(l0, tailP), yTail)
        quadPath.lineTo(proj.xAt(l1, tailP), yTail)
        quadPath.lineTo(proj.xAt(l1, headP), yHead)
        quadPath.lineTo(proj.xAt(l0, headP), yHead)
        quadPath.close()

        fillPaint.shader = null
        if (isDropped) {
            // Desaturated translucent gray for missed or early-released hold notes
            fillPaint.color = Color.argb(85, 130, 138, 152)
            canvas.drawPath(quadPath, fillPaint)
            strokePaint.color = Color.argb(105, 175, 182, 195)
            strokePaint.strokeWidth = 2f
            canvas.drawPath(quadPath, strokePaint)
            return
        }

        val c = skin.holdBody
        fillPaint.color = Color.argb(if (isHolding) 210 else 155, Color.red(c), Color.green(c), Color.blue(c))
        canvas.drawPath(quadPath, fillPaint)

        // Bright center energy core line inside hold ribbon
        strokePaint.color = Color.argb(if (isHolding) 250 else 190, 255, 250, 228)
        strokePaint.strokeWidth = if (isHolding) 5f else 3f
        canvas.drawLine(proj.xAt(lane + 0.5f, tailP), yTail, proj.xAt(lane + 0.5f, headP), yHead, strokePaint)

        strokePaint.strokeWidth = 2.5f
        canvas.drawPath(quadPath, strokePaint)
    }

    private fun drawHoldSlideRail(
        canvas: Canvas,
        proj: TrackProjection,
        rn: RuntimeNote,
        songTimeDouble: Double,
        approachMs: Double,
        skin: NoteSkin,
        isDropped: Boolean = false
    ) {
        val pts = rn.note.pathPoints
        if (pts.size < 2) return

        val isHolding = rn.state == NoteRuntimeState.HOLDING
        val songTimeMs = songTimeDouble.toLong()
        val clipStartMs = if (isDropped && rn.droppedAtMs > rn.note.timeMs) rn.droppedAtMs else songTimeMs
        path.reset()
        var started = false

        for (pt in pts) {
            val clampedT = when {
                isHolding && pt.timeMs < songTimeMs -> songTimeDouble
                isDropped && pt.timeMs < clipStartMs -> clipStartMs.toDouble()
                else -> pt.timeMs.toDouble()
            }
            val p = (1.0 - (clampedT - songTimeDouble) / approachMs).toFloat().coerceIn(0f, 1.22f)
            val laneF = when {
                isHolding && pt.timeMs < songTimeMs -> engine.expectedLaneFloatAt(rn.note, songTimeMs)
                isDropped && pt.timeMs < clipStartMs -> engine.expectedLaneFloatAt(rn.note, clipStartMs)
                else -> pt.lane.toFloat()
            }
            val x = proj.xAt(laneF + 0.5f, p)
            val y = proj.yAt(p)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        if (isDropped) {
            railGlowPaint.color = Color.argb(82, 130, 138, 152)
            railGlowPaint.strokeWidth = proj.laneWidthAtJudge * 0.20f
            canvas.drawPath(path, railGlowPaint)

            railGlowPaint.color = Color.argb(110, 180, 186, 196)
            railGlowPaint.strokeWidth = proj.laneWidthAtJudge * 0.065f
            canvas.drawPath(path, railGlowPaint)
            return
        }

        val c = skin.slideMain
        railGlowPaint.color = Color.argb(if (isHolding) 155 else 105, Color.red(c), Color.green(c), Color.blue(c))
        railGlowPaint.strokeWidth = proj.laneWidthAtJudge * 0.24f
        canvas.drawPath(path, railGlowPaint)

        railGlowPaint.color = skin.slideHighlight
        railGlowPaint.strokeWidth = proj.laneWidthAtJudge * 0.085f
        canvas.drawPath(path, railGlowPaint)

        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (a.lane != b.lane) {
                val pEnd = (1.0 - (b.timeMs - songTimeDouble) / approachMs).toFloat()
                if (pEnd in 0f..1.05f) {
                    val ex = proj.xAt(b.lane + 0.5f, pEnd)
                    val ey = proj.yAt(pEnd)
                    val dir = if (b.lane > a.lane) 1f else -1f
                    drawArrowTip(canvas, ex, ey, proj.scaleAt(pEnd) * proj.laneWidthAtJudge * 0.22f, dir, skin.slideHighlight)
                }
            }
        }
    }

    private fun drawCrossLaneSlideArrow(
        canvas: Canvas,
        proj: TrackProjection,
        fromLane: Int,
        toLane: Int,
        progress: Float,
        skin: NoteSkin
    ) {
        val y = proj.yAt(progress)
        val sx = proj.xAt(fromLane + 0.5f, progress)
        val ex = proj.xAt(toLane + 0.5f, progress)
        val scale = proj.scaleAt(progress)
        val dir = if (toLane > fromLane) 1f else -1f
        val c = skin.slideMain

        railGlowPaint.color = Color.argb(135, Color.red(c), Color.green(c), Color.blue(c))
        railGlowPaint.strokeWidth = max(10f, proj.laneWidthAtJudge * 0.22f * scale)
        canvas.drawLine(sx, y, ex, y, railGlowPaint)

        railGlowPaint.color = skin.slideHighlight
        railGlowPaint.strokeWidth = max(4f, proj.laneWidthAtJudge * 0.08f * scale)
        canvas.drawLine(sx, y, ex, y, railGlowPaint)

        drawArrowTip(canvas, ex, y, max(12f, proj.laneWidthAtJudge * 0.26f * scale), dir, skin.slideHighlight)
    }

    private fun drawArrowTip(canvas: Canvas, tipX: Float, tipY: Float, size: Float, dir: Float, color: Int) {
        path.reset()
        path.moveTo(tipX + size * 0.55f * dir, tipY)
        path.lineTo(tipX - size * 0.55f * dir, tipY - size * 0.65f)
        path.lineTo(tipX - size * 0.20f * dir, tipY)
        path.lineTo(tipX - size * 0.55f * dir, tipY + size * 0.65f)
        path.close()
        fillPaint.shader = null
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    /**
     * Draws genuinely distinct note shapes per skin (zero per-frame shader allocations!).
     */
    private fun drawStyledNoteHead(
        canvas: Canvas,
        proj: TrackProjection,
        laneFloat: Float,
        progress: Float,
        note: ChartNote,
        skin: NoteSkin,
        isHolding: Boolean,
        isDropped: Boolean = false
    ) {
        val left = proj.xAt(laneFloat + 0.06f, progress)
        val right = proj.xAt(laneFloat + 0.94f, progress)
        val cx = (left + right) * 0.5f
        val cy = proj.yAt(progress)
        val w = right - left
        val h = max(12f, w * 0.29f)

        fillPaint.shader = null
        if (isDropped) {
            // Translucent desaturated gray head for dropped / missed holds
            val r = h * 0.35f
            rectF.set(left, cy - h * 0.48f, right, cy + h * 0.48f)
            fillPaint.color = Color.argb(105, 132, 140, 152)
            canvas.drawRoundRect(rectF, r, r, fillPaint)
            strokePaint.color = Color.argb(120, 185, 192, 202)
            strokePaint.strokeWidth = 2f
            canvas.drawRoundRect(rectF, r, r, strokePaint)
            return
        }

        val mainColor = when (note.type) {
            NoteType.TAP -> skin.tapMain
            NoteType.HOLD -> skin.holdMain
            NoteType.SLIDE, NoteType.HOLD_SLIDE -> skin.slideMain
        }
        val highlightColor = when (note.type) {
            NoteType.TAP -> skin.tapHighlight
            NoteType.HOLD -> Color.rgb(255, 248, 220)
            NoteType.SLIDE, NoteType.HOLD_SLIDE -> skin.slideHighlight
        }

        fillPaint.shader = null

        when (skin.noteShape) {
            NoteGeometryStyle.CRYSTAL_CAPSULE -> {
                val r = h * 0.48f
                rectF.set(left - 4f, cy - h * 0.64f, right + 4f, cy + h * 0.64f)
                fillPaint.color = Color.argb(if (isHolding) 150 else 85, Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor))
                canvas.drawRoundRect(rectF, r * 1.4f, r * 1.4f, fillPaint)

                rectF.set(left, cy - h * 0.5f, right, cy + h * 0.5f)
                fillPaint.color = mainColor
                canvas.drawRoundRect(rectF, r, r, fillPaint)

                rectF.set(left + w * 0.08f, cy - h * 0.42f, right - w * 0.08f, cy - h * 0.04f)
                fillPaint.color = Color.argb(180, 255, 255, 255)
                canvas.drawRoundRect(rectF, r * 0.5f, r * 0.5f, fillPaint)

                // Center celestial diamond
                val ds = h * 0.28f
                path.reset()
                path.moveTo(cx, cy - ds); path.lineTo(cx + ds * 1.5f, cy)
                path.lineTo(cx, cy + ds); path.lineTo(cx - ds * 1.5f, cy); path.close()
                fillPaint.color = Color.WHITE
                canvas.drawPath(path, fillPaint)
            }

            NoteGeometryStyle.PIANO_BEVEL -> {
                // Classic 3D beveled arcade piano block (homage to Image 1!)
                val r = h * 0.22f
                rectF.set(left - 3f, cy - h * 0.62f, right + 3f, cy + h * 0.62f)
                fillPaint.color = Color.argb(95, Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor))
                canvas.drawRoundRect(rectF, r, r, fillPaint)

                rectF.set(left, cy - h * 0.52f, right, cy + h * 0.52f)
                fillPaint.color = mainColor
                canvas.drawRoundRect(rectF, r, r, fillPaint)

                // Crisp top highlight strip
                rectF.set(left + 4f, cy - h * 0.44f, right - 4f, cy - h * 0.08f)
                fillPaint.color = highlightColor
                canvas.drawRoundRect(rectF, r * 0.5f, r * 0.5f, fillPaint)

                // Double bright white arcade rim
                rectF.set(left, cy - h * 0.52f, right, cy + h * 0.52f)
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 3f
                canvas.drawRoundRect(rectF, r, r, strokePaint)
            }

            NoteGeometryStyle.SAKURA_PETAL -> {
                // Soft blossom jewel with central 4-petal sakura crest
                val r = h * 0.5f
                rectF.set(left - 5f, cy - h * 0.66f, right + 5f, cy + h * 0.66f)
                fillPaint.color = Color.argb(95, Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor))
                canvas.drawRoundRect(rectF, r * 1.4f, r * 1.4f, fillPaint)

                rectF.set(left, cy - h * 0.5f, right, cy + h * 0.5f)
                fillPaint.color = mainColor
                canvas.drawRoundRect(rectF, r, r, fillPaint)

                rectF.set(left + w * 0.06f, cy - h * 0.36f, right - w * 0.06f, cy + h * 0.36f)
                strokePaint.color = highlightColor
                strokePaint.strokeWidth = 2.2f
                canvas.drawRoundRect(rectF, r * 0.8f, r * 0.8f, strokePaint)

                // Central 4-petal flower emblem
                val pr = h * 0.24f
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx - pr * 0.7f, cy, pr * 0.55f, fillPaint)
                canvas.drawCircle(cx + pr * 0.7f, cy, pr * 0.55f, fillPaint)
                canvas.drawCircle(cx, cy - pr * 0.6f, pr * 0.55f, fillPaint)
                canvas.drawCircle(cx, cy + pr * 0.6f, pr * 0.55f, fillPaint)
            }

            NoteGeometryStyle.MECHA_HEX -> {
                // Sharp chamfered tactical hexagon prism with glowing core line
                val cham = h * 0.55f
                path.reset()
                path.moveTo(left + cham, cy - h * 0.52f)
                path.lineTo(right - cham, cy - h * 0.52f)
                path.lineTo(right, cy)
                path.lineTo(right - cham, cy + h * 0.52f)
                path.lineTo(left + cham, cy + h * 0.52f)
                path.lineTo(left, cy)
                path.close()

                fillPaint.color = mainColor
                canvas.drawPath(path, fillPaint)

                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 2.6f
                canvas.drawPath(path, strokePaint)

                // Horizontal laser core bar
                strokePaint.color = highlightColor
                strokePaint.strokeWidth = max(3f, h * 0.22f)
                canvas.drawLine(left + cham * 1.1f, cy, right - cham * 1.1f, cy, strokePaint)
            }

            NoteGeometryStyle.MATH_MANUSCRIPT -> {
                // Monochrome Obsidian Chalkboard Tile with crisp white matrix brackets & math glyph!
                rectF.set(left, cy - h * 0.54f, right, cy + h * 0.54f)
                fillPaint.color = if (note.type == NoteType.TAP) Color.rgb(16, 18, 22) else Color.rgb(235, 238, 244)
                canvas.drawRect(rectF, fillPaint)

                // Crisp monochrome frame
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 2.6f
                canvas.drawRect(rectF, strokePaint)

                // Matrix square-bracket corner accents on left & right
                val bk = max(6f, w * 0.12f)
                strokePaint.strokeWidth = 4.2f
                strokePaint.color = if (note.type == NoteType.TAP) Color.WHITE else Color.rgb(18, 20, 24)
                canvas.drawLine(left + 3f, cy - h * 0.50f, left + 3f + bk, cy - h * 0.50f, strokePaint)
                canvas.drawLine(left + 3f, cy + h * 0.50f, left + 3f + bk, cy + h * 0.50f, strokePaint)
                canvas.drawLine(right - 3f - bk, cy - h * 0.50f, right - 3f, cy - h * 0.50f, strokePaint)
                canvas.drawLine(right - 3f - bk, cy + h * 0.50f, right - 3f, cy + h * 0.50f, strokePaint)

                // Engraved math symbol in the center
                val sym = laneMathSymbols[note.lane.coerceIn(0, laneMathSymbols.lastIndex)]
                mathNotePaint.color = if (note.type == NoteType.TAP) Color.WHITE else Color.rgb(12, 14, 18)
                mathNotePaint.textSize = max(12f, h * 0.78f)
                canvas.drawText(sym, cx, cy + mathNotePaint.textSize * 0.34f, mathNotePaint)
            }
        }
    }

    private fun drawComboAndJudgment(canvas: Canvas, proj: TrackProjection, wallNowMs: Long, skin: NoteSkin) {
        val h = proj.height
        val lastJ = engine.lastJudgment
        val ageMs = wallNowMs - engine.lastJudgmentWallMs

        if (lastJ != null && ageMs in 0L..500L) {
            val frac = ageMs / 500f
            val alpha = ((1f - frac * frac) * 255).toInt().coerceIn(0, 255)
            val pop = if (frac < 0.16f) 1.22f - frac * 1.2f else 1.0f
            val baseC = if (skin == NoteSkin.EULER_CHALK) {
                if (lastJ == com.astraecho.rhythm.model.Judgment.MISS) Color.rgb(145, 150, 160) else Color.WHITE
            } else {
                lastJ.colorHex.toInt()
            }
            val label = lastJ.styledLabel(skin)
            val col = Color.argb(alpha, Color.red(baseC), Color.green(baseC), Color.blue(baseC))
            drawGlowText(canvas, label, proj.centerX, h * 0.34f - frac * 12f, h * 0.064f * pop, col, judgeTextPaint)
        }

        if (engine.combo >= 2) {
            drawGlowText(canvas, engine.combo.toString(), proj.centerX, h * 0.48f, h * 0.132f, Color.WHITE, comboTextPaint, glowColor = skin.railColor)
        }
    }

    /**
     * Fast 2-pass GPU-friendly glow text (replaces expensive software Gaussian Blur ShadowLayer!).
     */
    private fun drawGlowText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        paint: Paint,
        glowColor: Int = color
    ) {
        paint.textSize = size
        val alpha = Color.alpha(color)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.14f
        paint.color = Color.argb((alpha * 0.35f).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
        canvas.drawText(text, x, y, paint)

        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawText(text, x, y, paint)
    }
}
