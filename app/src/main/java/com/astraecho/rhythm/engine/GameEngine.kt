package com.astraecho.rhythm.engine

import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartNote
import com.astraecho.rhythm.model.FlickDirection
import com.astraecho.rhythm.model.GameResult
import com.astraecho.rhythm.model.Judgment
import com.astraecho.rhythm.model.NoteType
import kotlin.math.abs

enum class NoteRuntimeState {
    UPCOMING,
    HOLDING,
    COMPLETED,
    MISSED
}

data class RuntimeNote(
    val note: ChartNote,
    var state: NoteRuntimeState = NoteRuntimeState.UPCOMING,
    var holdingPointerId: Int = -1,
    var currentPointerLane: Int = note.lane,
    var lastTickMs: Long = 0L,
    var lastBendLane: Int = note.lane,
    var droppedAtMs: Long = -1L // Timestamp when a HOLD / HOLD_SLIDE was missed or released early
)

sealed interface VisualEvent {
    data class TapHit(val lane: Int, val judgment: Judgment) : VisualEvent
    data class HoldTick(val lane: Float, val isHoldSlide: Boolean) : VisualEvent
    data class SlideBurst(val fromLane: Int, val toLane: Int, val judgment: Judgment) : VisualEvent
}

data class HudSnapshot(
    val score: Int = 0,
    val combo: Int = 0,
    val maxCombo: Int = 0,
    val accuracy: Float = 100f,
    val progress: Float = 0f,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastJudgment: Judgment? = null,
    val lastJudgmentTimeMs: Long = 0L
)

/**
 * Pure rhythm game judgment engine supporting:
 * - TAP (按键)
 * - HOLD (长条)
 * - SLIDE (跨轨道滑动)
 * - HOLD_SLIDE (长按加滑动)
 * - Cross-lane Sweep (滑动扫过多个轨道按顺序触发按键!)
 * - Clean instant reset() for zero-latency Restart!
 */
class GameEngine(
    val chart: BeatmapChart
) {
    companion object {
        const val PERFECT_WINDOW_MS = 50L
        const val GREAT_WINDOW_MS = 98L
        const val GOOD_WINDOW_MS = 148L
        const val SLIDE_WINDOW_MS = 175L
        const val MISS_AFTER_MS = 165L
        const val HOLD_RELEASE_GRACE_MS = 145L
        const val HOLD_TICK_INTERVAL_MS = 120L
    }

    val runtimeNotes: List<RuntimeNote> = chart.notes.map { RuntimeNote(it) }
    val pendingVisualEvents: MutableList<VisualEvent> = mutableListOf()

    var score: Int = 0
        private set
    var combo: Int = 0
        private set
    var maxCombo: Int = 0
        private set
    var perfectCount: Int = 0
        private set
    var greatCount: Int = 0
        private set
    var goodCount: Int = 0
        private set
    var missCount: Int = 0
        private set

    var lastJudgment: Judgment? = null
        private set
    var lastJudgmentWallMs: Long = 0L
        private set

    private val totalJudged: Int get() = perfectCount + greatCount + goodCount + missCount

    fun reset() {
        for (rn in runtimeNotes) {
            rn.state = NoteRuntimeState.UPCOMING
            rn.holdingPointerId = -1
            rn.currentPointerLane = rn.note.lane
            rn.lastTickMs = 0L
            rn.lastBendLane = rn.note.lane
            rn.droppedAtMs = -1L
        }
        pendingVisualEvents.clear()
        score = 0
        combo = 0
        maxCombo = 0
        perfectCount = 0
        greatCount = 0
        goodCount = 0
        missCount = 0
        lastJudgment = null
        lastJudgmentWallMs = 0L
    }

    fun drainVisualEvents(out: MutableList<VisualEvent>) {
        if (pendingVisualEvents.isNotEmpty()) {
            out.addAll(pendingVisualEvents)
            pendingVisualEvents.clear()
        }
    }

    fun expectedLaneFloatAt(note: ChartNote, songTimeMs: Long): Float {
        val pts = note.pathPoints
        if (pts.size < 2) return note.lane.toFloat()
        if (songTimeMs <= pts.first().timeMs) return pts.first().lane.toFloat()
        if (songTimeMs >= pts.last().timeMs) return pts.last().lane.toFloat()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (songTimeMs in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                val t = (songTimeMs - a.timeMs).toFloat() / span.toFloat()
                return a.lane + (b.lane - a.lane) * t
            }
        }
        return pts.last().lane.toFloat()
    }

    fun update(songTimeMs: Long, wallTimeMs: Long) {
        for (rn in runtimeNotes) {
            when (rn.state) {
                NoteRuntimeState.UPCOMING -> {
                    val lateBy = songTimeMs - rn.note.timeMs
                    if (lateBy > MISS_AFTER_MS) {
                        rn.state = NoteRuntimeState.MISSED
                        rn.droppedAtMs = rn.note.timeMs
                        registerJudgment(Judgment.MISS, wallTimeMs)
                    }
                }

                NoteRuntimeState.HOLDING -> {
                    val expectedLane = expectedLaneFloatAt(rn.note, songTimeMs)
                    val isHoldSlide = rn.note.type == NoteType.HOLD_SLIDE

                    if (isHoldSlide) {
                        val expectedInt = kotlin.math.round(expectedLane).toInt()
                        if (expectedInt != rn.lastBendLane) {
                            pendingVisualEvents.add(
                                VisualEvent.SlideBurst(rn.lastBendLane, expectedInt, Judgment.PERFECT)
                            )
                            rn.lastBendLane = expectedInt
                        }
                    }

                    if (songTimeMs >= rn.note.endTimeMs - 70L) {
                        rn.state = NoteRuntimeState.COMPLETED
                        rn.holdingPointerId = -1
                        score += 280 + combo * 4
                        pendingVisualEvents.add(
                            VisualEvent.TapHit(kotlin.math.round(expectedLane).toInt(), Judgment.PERFECT)
                        )
                    } else if (songTimeMs - rn.lastTickMs >= HOLD_TICK_INTERVAL_MS) {
                        rn.lastTickMs = songTimeMs
                        score += 90 + combo * 2
                        pendingVisualEvents.add(VisualEvent.HoldTick(expectedLane, isHoldSlide))
                    }
                }

                else -> Unit
            }
        }
    }

    fun onLanePress(lane: Int, pointerId: Int, songTimeMs: Long, wallTimeMs: Long): Judgment? {
        // If a HOLD/HOLD_SLIDE was detached by a system 3-finger ACTION_CANCEL, re-attach seamlessly!
        val detachedHold = runtimeNotes.firstOrNull { rn ->
            rn.state == NoteRuntimeState.HOLDING &&
                rn.holdingPointerId == -2 &&
                (rn.note.lane == lane || kotlin.math.round(expectedLaneFloatAt(rn.note, songTimeMs)).toInt() == lane)
        }
        if (detachedHold != null) {
            detachedHold.holdingPointerId = pointerId
            detachedHold.currentPointerLane = lane
            return null
        }
        return triggerNoteOnLane(lane, pointerId, songTimeMs, wallTimeMs)
    }

    /**
     * Triggered when a finger sweeps across into a new [lane] during ACTION_MOVE.
     * Allows sweeping across e.g. lanes 0 -> 1 -> 2 in one smooth motion to hit simultaneous or staggered notes!
     */
    fun onLaneSweepEnter(lane: Int, pointerId: Int, songTimeMs: Long, wallTimeMs: Long): Judgment? {
        // Don't hijack a finger that is actively holding a HOLD / HOLD_SLIDE rail
        val isHoldingRail = runtimeNotes.any {
            it.state == NoteRuntimeState.HOLDING && it.holdingPointerId == pointerId
        }
        if (isHoldingRail) return null
        return triggerNoteOnLane(lane, pointerId, songTimeMs, wallTimeMs)
    }

    private fun triggerNoteOnLane(lane: Int, pointerId: Int, songTimeMs: Long, wallTimeMs: Long): Judgment? {
        val candidate = runtimeNotes.firstOrNull { rn ->
            rn.state == NoteRuntimeState.UPCOMING &&
                rn.note.lane == lane &&
                rn.note.type != NoteType.SLIDE &&
                abs(songTimeMs - rn.note.timeMs) <= GOOD_WINDOW_MS
        } ?: return null

        val delta = abs(songTimeMs - candidate.note.timeMs)
        val judgment = when {
            delta <= PERFECT_WINDOW_MS -> Judgment.PERFECT
            delta <= GREAT_WINDOW_MS -> Judgment.GREAT
            else -> Judgment.GOOD
        }

        if (candidate.note.type == NoteType.HOLD || candidate.note.type == NoteType.HOLD_SLIDE) {
            candidate.state = NoteRuntimeState.HOLDING
            candidate.holdingPointerId = pointerId
            candidate.currentPointerLane = lane
            candidate.lastTickMs = songTimeMs
            candidate.lastBendLane = lane
        } else {
            candidate.state = NoteRuntimeState.COMPLETED
        }

        registerJudgment(judgment, wallTimeMs)
        pendingVisualEvents.add(VisualEvent.TapHit(lane, judgment))
        return judgment
    }

    fun onPointerMoveLane(pointerId: Int, newLane: Int) {
        for (rn in runtimeNotes) {
            if (rn.state == NoteRuntimeState.HOLDING && rn.holdingPointerId == pointerId) {
                rn.currentPointerLane = newLane
            }
        }
    }

    fun onLaneSlide(
        fromLane: Int,
        direction: FlickDirection,
        songTimeMs: Long,
        wallTimeMs: Long
    ): Judgment? {
        val candidate = runtimeNotes.firstOrNull { rn ->
            rn.state == NoteRuntimeState.UPCOMING &&
                rn.note.type == NoteType.SLIDE &&
                abs(rn.note.lane - fromLane) <= 1 &&
                rn.note.flickDirection == direction &&
                abs(songTimeMs - rn.note.timeMs) <= SLIDE_WINDOW_MS
        } ?: return null

        val delta = abs(songTimeMs - candidate.note.timeMs)
        val judgment = when {
            delta <= PERFECT_WINDOW_MS + 20L -> Judgment.PERFECT
            delta <= GREAT_WINDOW_MS + 24L -> Judgment.GREAT
            else -> Judgment.GOOD
        }

        candidate.state = NoteRuntimeState.COMPLETED
        registerJudgment(judgment, wallTimeMs)
        pendingVisualEvents.add(
            VisualEvent.SlideBurst(candidate.note.lane, candidate.note.slideTargetLane, judgment)
        )
        return judgment
    }

    fun onPointerRelease(
        pointerId: Int,
        songTimeMs: Long,
        wallTimeMs: Long,
        replacementPointerId: Int = -1
    ) {
        for (rn in runtimeNotes) {
            if (rn.state == NoteRuntimeState.HOLDING && rn.holdingPointerId == pointerId) {
                if (replacementPointerId >= 0) {
                    // Another finger is still pressing this lane — transfer ownership cleanly!
                    rn.holdingPointerId = replacementPointerId
                    continue
                }
                rn.holdingPointerId = -1
                if (songTimeMs >= rn.note.endTimeMs - HOLD_RELEASE_GRACE_MS) {
                    rn.state = NoteRuntimeState.COMPLETED
                    score += 220
                    pendingVisualEvents.add(VisualEvent.TapHit(rn.note.slideTargetLane, Judgment.PERFECT))
                } else {
                    rn.state = NoteRuntimeState.MISSED
                    rn.droppedAtMs = songTimeMs
                    registerJudgment(Judgment.MISS, wallTimeMs)
                }
            }
        }
    }

    /**
     * Called when Android OS fires ACTION_CANCEL (e.g. 3-finger screenshot gesture).
     * Detaches pointer IDs gracefully without instantly failing active holds.
     */
    fun onSystemTouchCancel() {
        for (rn in runtimeNotes) {
            if (rn.state == NoteRuntimeState.HOLDING) {
                rn.holdingPointerId = -2
            }
        }
    }

    private fun registerJudgment(judgment: Judgment, wallTimeMs: Long) {
        lastJudgment = judgment
        lastJudgmentWallMs = wallTimeMs
        when (judgment) {
            Judgment.PERFECT -> {
                perfectCount++
                combo++
                score += 1000 + combo * 10
            }
            Judgment.GREAT -> {
                greatCount++
                combo++
                score += 750 + combo * 7
            }
            Judgment.GOOD -> {
                goodCount++
                combo++
                score += 400 + combo * 4
            }
            Judgment.MISS -> {
                missCount++
                combo = 0
            }
        }
        if (combo > maxCombo) maxCombo = combo
    }

    fun currentAccuracy(): Float {
        if (totalJudged == 0) return 100f
        val weighted = perfectCount * 1.0f + greatCount * 0.75f + goodCount * 0.40f
        return (weighted / totalJudged * 100f).coerceIn(0f, 100f)
    }

    fun snapshot(songTimeMs: Long): HudSnapshot {
        val progress = if (chart.durationMs > 0) {
            (songTimeMs.toFloat() / chart.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
        return HudSnapshot(
            score = score,
            combo = combo,
            maxCombo = maxCombo,
            accuracy = currentAccuracy(),
            progress = progress,
            currentTimeMs = songTimeMs.coerceIn(0L, chart.durationMs.coerceAtLeast(0L)),
            durationMs = chart.durationMs.coerceAtLeast(0L),
            lastJudgment = lastJudgment,
            lastJudgmentTimeMs = lastJudgmentWallMs
        )
    }

    fun isFinished(songTimeMs: Long): Boolean {
        val lastNoteEnd = chart.notes.maxOfOrNull { it.endTimeMs } ?: 0L
        val targetEnd = maxOf(chart.durationMs, lastNoteEnd + 900L)
        return songTimeMs >= targetEnd
    }

    fun buildResult(): GameResult = GameResult(
        songTitle = chart.title,
        difficulty = chart.difficulty,
        laneCount = chart.laneCount,
        onlyTapAndHold = chart.onlyTapAndHold,
        score = score,
        maxCombo = maxCombo,
        perfectCount = perfectCount,
        greatCount = greatCount,
        goodCount = goodCount,
        missCount = missCount,
        totalNotes = chart.notes.size
    )
}
