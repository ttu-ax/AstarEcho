package com.astraecho.rhythm

import com.astraecho.rhythm.audio.AudioFeatureFrames
import com.astraecho.rhythm.audio.AutoChartGenerator
import com.astraecho.rhythm.audio.LinkMediaImporter
import com.astraecho.rhythm.engine.GameEngine
import com.astraecho.rhythm.engine.TrackProjection
import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartConfig
import com.astraecho.rhythm.model.ChartNote
import com.astraecho.rhythm.model.Difficulty
import com.astraecho.rhythm.model.FlickDirection
import com.astraecho.rhythm.model.Judgment
import com.astraecho.rhythm.model.NoteType
import com.astraecho.rhythm.model.SlidePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class AstraEchoUnitTest {

    private fun buildSyntheticBeatPcm(durationSec: Int = 18, bpm: Int = 128, sr: Int = 22050, startOffsetMs: Int = 120): FloatArray {
        val samples = FloatArray(durationSec * sr)
        val beatSamples = (sr * 60.0 / bpm).toInt()
        var pos = (startOffsetMs * sr) / 1000
        var beatIdx = 0
        while (pos < samples.size) {
            val isAccent = beatIdx % 4 == 0
            val freq = if (isAccent) 160.0 else if (beatIdx % 2 == 1) 4200.0 else 520.0
            val len = if (beatIdx % 4 == 2) (sr * 0.65).toInt() else sr / 8
            for (i in 0 until len) {
                if (pos + i >= samples.size) break
                val t = i.toDouble() / sr
                val env = if (beatIdx % 4 == 2) 0.80 else exp(-t * 14.0)
                samples[pos + i] += (sin(2.0 * PI * freq * t) * env).toFloat()
            }
            pos += beatSamples
            beatIdx++
        }
        return samples
    }

    @Test
    fun autoChartCapturesVeryFirstNoteAndGeneratesHolds() {
        val pcm = buildSyntheticBeatPcm(startOffsetMs = 120)
        val features = AutoChartGenerator.analyzePcm(pcm, 22050)

        for (lanes in listOf(4, 5, 6)) {
            val chartNoSlide = AutoChartGenerator.generateChart(
                songId = "test_song",
                title = "Test",
                artist = "Unit",
                features = features,
                config = ChartConfig(laneCount = lanes, difficulty = Difficulty.HARD, onlyTapAndHold = true)
            )
            assertTrue("Should generate notes for ${lanes}K", chartNoSlide.notes.isNotEmpty())
            assertTrue(
                "Very first note near 120ms must be captured (got ${chartNoSlide.notes.first().timeMs}ms)",
                chartNoSlide.notes.first().timeMs <= 260L
            )
            assertTrue("Should generate HOLD notes reliably", chartNoSlide.notes.any { it.type == NoteType.HOLD })
            assertFalse(
                "onlyTapAndHold=true must never generate SLIDE or HOLD_SLIDE",
                chartNoSlide.notes.any { it.type == NoteType.SLIDE || it.type == NoteType.HOLD_SLIDE }
            )

            val chartAll = AutoChartGenerator.generateChart(
                songId = "test_song",
                title = "Test",
                artist = "Unit",
                features = features,
                config = ChartConfig(laneCount = lanes, difficulty = Difficulty.MASTER, onlyTapAndHold = false)
            )
            assertTrue("Should generate cross-lane SLIDE or HOLD_SLIDE", chartAll.notes.any { it.type == NoteType.SLIDE || it.type == NoteType.HOLD_SLIDE })
            assertTrue(chartAll.notes.all { it.lane in 0 until lanes && it.slideTargetLane in 0 until lanes })
        }
    }

    @Test
    fun featureFramesPersistToBinaryFileCleanly() {
        val pcm = buildSyntheticBeatPcm(durationSec = 4)
        val original = AutoChartGenerator.analyzePcm(pcm, 22050)
        val tmp = File.createTempFile("astra_feat_test", ".bin")
        try {
            original.writeToFile(tmp)
            val restored = AudioFeatureFrames.readFromFile(tmp)
            assertNotNull(restored)
            assertEquals(original.durationMs, restored!!.durationMs)
            assertEquals(original.rmsEnergy.size, restored.rmsEnergy.size)
            assertEquals(original.spectralFlux[5], restored.spectralFlux[5], 0.0001f)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun landscapeAndTabletTrackProjectionIsSeamless() {
        val tablet = TrackProjection(width = 2048f, height = 1536f, laneCount = 4, tiltDegrees = 42f)
        val phone = TrackProjection(width = 2400f, height = 1080f, laneCount = 6, tiltDegrees = 42f)

        assertTrue(tablet.topWidth < tablet.bottomWidth * 0.6f)
        assertTrue(tablet.keyBottomWidth > tablet.bottomWidth)
        assertTrue(phone.bottomWidth < phone.width * 0.78f)
    }

    @Test
    fun gameEngineSupportsSweepAcrossSimultaneousLanesAndCleanReset() {
        val notes = listOf(
            ChartNote(1, timeMs = 1000L, lane = 0, type = NoteType.TAP),
            ChartNote(2, timeMs = 1000L, lane = 1, type = NoteType.TAP),
            ChartNote(3, timeMs = 1000L, lane = 2, type = NoteType.TAP),
            ChartNote(4, timeMs = 2000L, lane = 1, type = NoteType.HOLD, durationMs = 600L),
            ChartNote(5, timeMs = 3000L, lane = 2, type = NoteType.SLIDE, slideTargetLane = 3),
            ChartNote(
                6, timeMs = 4000L, lane = 1, type = NoteType.HOLD_SLIDE, durationMs = 900L,
                slideTargetLane = 2,
                pathPoints = listOf(
                    SlidePoint(4000L, 1), SlidePoint(4400L, 1),
                    SlidePoint(4470L, 2), SlidePoint(4900L, 2)
                )
            )
        )
        val chart = BeatmapChart("s1", "T", "A", 128, 6000L, 4, Difficulty.NORMAL, false, notes)
        val engine = GameEngine(chart)

        // Finger presses lane 0 then sweeps quickly across lane 1 -> lane 2!
        assertEquals(Judgment.PERFECT, engine.onLanePress(0, 1, 990L, 990L))
        assertEquals(Judgment.PERFECT, engine.onLaneSweepEnter(1, 1, 1005L, 1005L))
        assertEquals(Judgment.PERFECT, engine.onLaneSweepEnter(2, 1, 1020L, 1020L))
        assertEquals(3, engine.combo)

        // Hold + Slide + HoldSlide
        assertEquals(Judgment.GREAT, engine.onLanePress(1, 2, 2060L, 2060L))
        engine.update(2580L, 2580L)
        assertEquals(Judgment.PERFECT, engine.onLaneSlide(2, FlickDirection.RIGHT, 3020L, 3020L))
        assertEquals(Judgment.PERFECT, engine.onLanePress(1, 3, 4005L, 4005L))
        engine.onPointerMoveLane(3, 2)
        engine.update(4500L, 4500L)
        engine.update(4860L, 4860L)

        assertEquals(6, engine.combo)
        assertEquals(0, engine.missCount)

        // Instant Restart reset() check
        engine.reset()
        assertEquals(0, engine.score)
        assertEquals(0, engine.combo)
        assertEquals(0, engine.missCount)
        assertEquals(Judgment.PERFECT, engine.onLanePress(0, 1, 1000L, 1000L))
    }

    @Test
    fun holdingLongNoteWhilePressingTwoOtherNotesAndSystemCancelPreservesHold() {
        val notes = listOf(
            ChartNote(1, timeMs = 1000L, lane = 0, type = NoteType.HOLD, durationMs = 1000L),
            ChartNote(2, timeMs = 1400L, lane = 1, type = NoteType.TAP),
            ChartNote(3, timeMs = 1400L, lane = 2, type = NoteType.TAP)
        )
        val chart = BeatmapChart("s2", "M", "A", 120, 4000L, 4, Difficulty.HARD, true, notes)
        val engine = GameEngine(chart)

        // Finger 0 holds long note on lane 0
        assertEquals(Judgment.PERFECT, engine.onLanePress(0, 0, 1005L, 1005L))
        // Fingers 1 & 2 simultaneously tap lanes 1 and 2 while lane 0 is held!
        assertEquals(Judgment.PERFECT, engine.onLanePress(1, 1, 1402L, 1402L))
        assertEquals(Judgment.PERFECT, engine.onLanePress(2, 2, 1404L, 1404L))
        engine.onPointerRelease(1, 1440L, 1440L)
        engine.onPointerRelease(2, 1445L, 1445L)

        // Even if OS fires 3-finger ACTION_CANCEL, active hold must NOT fail!
        engine.onSystemTouchCancel()
        engine.update(1960L, 1960L)

        assertEquals(3, engine.combo)
        assertEquals(0, engine.missCount)
        assertTrue(engine.buildResult().isFullCombo)
    }

    @Test
    fun quietIntroBeforeLoudEdmDropStillCapturesDenseIntroPlucks() {
        val sr = 22050
        val totalSec = 16
        val samples = FloatArray(totalSec * sr)

        // 0s..8s: Quiet kalimba-like plucks (amplitude 0.065) every 230ms
        var tMs = 200
        while (tMs < 7800) {
            val start = (tMs * sr) / 1000
            val freq = 580.0 + ((tMs / 230) % 5) * 140.0
            for (i in 0 until sr / 6) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / sr
                samples[start + i] += (sin(2.0 * PI * freq * t) * exp(-t * 16.0) * 0.065).toFloat()
            }
            tMs += 230
        }

        // 8s..16s: Super-loud EDM drop (amplitude 0.92, ~14x louder!)
        tMs = 8000
        while (tMs < 15600) {
            val start = (tMs * sr) / 1000
            for (i in 0 until sr / 5) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / sr
                samples[start + i] += (sin(2.0 * PI * 140.0 * t) * exp(-t * 10.0) * 0.92).toFloat()
            }
            tMs += 210
        }

        val features = AutoChartGenerator.analyzePcm(samples, sr)
        val master = AutoChartGenerator.generateChart(
            songId = "quiet_intro_test",
            title = "QuietIntro",
            artist = "Test",
            features = features,
            config = ChartConfig(laneCount = 4, difficulty = Difficulty.MASTER, onlyTapAndHold = false)
        )
        val easy = AutoChartGenerator.generateChart(
            songId = "quiet_intro_test",
            title = "QuietIntro",
            artist = "Test",
            features = features,
            config = ChartConfig(laneCount = 4, difficulty = Difficulty.EASY, onlyTapAndHold = false)
        )

        val introNotesMaster = master.notes.count { it.timeMs < 8000L }
        assertTrue(
            "Quiet intro (0-8s) must capture dense plucks on MASTER even before a 14x louder drop (got $introNotesMaster)",
            introNotesMaster >= 20
        )
        assertTrue(
            "MASTER (${master.notes.size}) should have noticeably more notes than EASY (${easy.notes.size})",
            master.notes.size > easy.notes.size * 1.3
        )
    }

    @Test
    fun pressingAllFourLanesSimultaneouslyHitsAllFourNotes() {
        val notes = listOf(
            ChartNote(1, timeMs = 1000L, lane = 0, type = NoteType.TAP),
            ChartNote(2, timeMs = 1000L, lane = 1, type = NoteType.TAP),
            ChartNote(3, timeMs = 1000L, lane = 2, type = NoteType.TAP),
            ChartNote(4, timeMs = 1000L, lane = 3, type = NoteType.TAP)
        )
        val chart = BeatmapChart("s4", "Quad", "A", 120, 3000L, 4, Difficulty.MASTER, true, notes)
        val engine = GameEngine(chart)

        assertEquals(Judgment.PERFECT, engine.onLanePress(0, 0, 1000L, 1000L))
        assertEquals(Judgment.PERFECT, engine.onLanePress(1, 1, 1002L, 1002L))
        assertEquals(Judgment.PERFECT, engine.onLanePress(2, 2, 1004L, 1004L))
        assertEquals(Judgment.PERFECT, engine.onLanePress(3, 3, 1006L, 1006L))
        assertEquals(4, engine.combo)
        assertEquals(0, engine.missCount)
        assertTrue(engine.buildResult().isFullCombo)
    }

    @Test
    fun pressingAllSixLanesSimultaneouslyIn6KWorksCleanly() {
        val notes = (0 until 6).map { lane ->
            ChartNote(lane + 1, timeMs = 1000L, lane = lane, type = NoteType.TAP)
        }
        val chart = BeatmapChart("s6", "Hex", "A", 120, 3000L, 6, Difficulty.MASTER, true, notes)
        val engine = GameEngine(chart)

        for (lane in 0 until 6) {
            assertEquals(Judgment.PERFECT, engine.onLanePress(lane, lane, 1000L + lane, 1000L + lane))
        }
        assertEquals(6, engine.combo)
        assertEquals(0, engine.missCount)
        assertTrue(engine.buildResult().isFullCombo)

        // Verify perspective-exact (x, y) touch maps all 6 angled key centers to lanes 0..5
        val proj = TrackProjection(2400f, 1080f, 6, 45f)
        val keyMidY = (proj.judgeY + proj.bottomY) * 0.5f
        for (lane in 0 until 6) {
            val midX = (proj.xAt(lane + 0.5f, 1f) + proj.keyBottomXAt(lane + 0.5f)) * 0.5f
            assertEquals(lane, proj.laneIndexForTouch(midX, keyMidY))
        }
    }

    @Test
    fun earlyReleasedHoldMarksDroppedAtTimestampForGrayRendering() {
        val hold = ChartNote(1, timeMs = 1000L, lane = 1, type = NoteType.HOLD, durationMs = 1200L)
        val chart = BeatmapChart("h1", "Hold", "A", 120, 4000L, 4, Difficulty.NORMAL, true, listOf(hold))
        val engine = GameEngine(chart)

        assertEquals(Judgment.PERFECT, engine.onLanePress(1, 5, 1000L, 1000L))
        // Release way too early at 1350ms (end is 2200ms)
        engine.onPointerRelease(5, 1350L, 1350L)

        assertEquals(1, engine.missCount)
        assertEquals(1350L, engine.runtimeNotes.first().droppedAtMs)
    }

    @Test
    fun linkImporterExtractsDouyinAndDirectUrlsFromShareText() {
        val shareText = """
            4.89 复制打开抖音，看看【测试音乐】的作品 https://v.douyin.com/iRNB8x9K/ 好好听！
            还有这首直链：https://example.com/audio/track.mp3。
        """.trimIndent()
        val urls = LinkMediaImporter.extractUrls(shareText)
        assertEquals(2, urls.size)
        assertEquals("https://v.douyin.com/iRNB8x9K/", urls[0])
        assertEquals("https://example.com/audio/track.mp3", urls[1])
        assertTrue(LinkMediaImporter.isDouyinUrl(urls[0]))
        assertFalse(LinkMediaImporter.isDouyinUrl(urls[1]))
    }

    @Test
    fun autoChartDistributesLoadEvenlyAcrossAllFourLanesEvenForLowPitchSong() {
        // Bass-heavy song (160Hz-450Hz) whose raw centroid used to cluster on lanes 0 & 1!
        val sr = 22050
        val samples = FloatArray(16 * sr)
        var tMs = 150
        var idx = 0
        while (tMs < 15500) {
            val start = (tMs * sr) / 1000
            val freq = 160.0 + (idx % 4) * 75.0
            for (i in 0 until sr / 6) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / sr
                samples[start + i] += (sin(2.0 * PI * freq * t) * exp(-t * 14.0) * 0.7).toFloat()
            }
            tMs += 240
            idx++
        }

        val features = AutoChartGenerator.analyzePcm(samples, sr)
        val chart = AutoChartGenerator.generateChart(
            songId = "balance_test",
            title = "Balance",
            artist = "Test",
            features = features,
            config = ChartConfig(laneCount = 4, difficulty = Difficulty.HARD, onlyTapAndHold = false)
        )
        val counts = IntArray(4)
        chart.notes.forEach { counts[it.lane]++ }
        val total = chart.notes.size.toFloat()
        assertTrue("Should generate enough notes to measure balance", total >= 30f)
        for (lane in 0 until 4) {
            val ratio = counts[lane] / total
            assertTrue(
                "Lane $lane should have balanced load (>=15%), got ${(ratio * 100).toInt()}% (${counts.toList()})",
                ratio >= 0.15f
            )
        }
    }

    @Test
    fun holdsRespectMinDurationAndDrumHitsCreateAdjacentDoubleTaps() {
        val sr = 22050
        val samples = FloatArray(16 * sr)
        // 0..8s: Calm section with genuine 650ms sustained notes (amplitude 0.25)
        var tMs = 300
        while (tMs < 7200) {
            val start = (tMs * sr) / 1000
            for (i in 0 until (sr * 0.68).toInt()) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / sr
                samples[start + i] += (sin(2.0 * PI * 330.0 * t) * 0.25).toFloat()
            }
            tMs += 1100
        }
        // 8..16s: Intense chorus with heavy 110Hz kick drums (amplitude 0.92)
        tMs = 8200
        while (tMs < 15400) {
            val start = (tMs * sr) / 1000
            for (i in 0 until (sr * 0.22).toInt()) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / sr
                samples[start + i] += (sin(2.0 * PI * 110.0 * t) * exp(-t * 10.0) * 0.92).toFloat()
            }
            tMs += 420
        }

        val features = AutoChartGenerator.analyzePcm(samples, sr)
        val chart = AutoChartGenerator.generateChart(
            songId = "drum_hold_test",
            title = "DrumHold",
            artist = "Test",
            features = features,
            config = ChartConfig(laneCount = 4, difficulty = Difficulty.HARD, onlyTapAndHold = false)
        )

        // All holds must meet the strict >= 460ms standard
        val holds = chart.notes.filter { it.type == NoteType.HOLD || it.type == NoteType.HOLD_SLIDE }
        assertTrue(
            "Every HOLD note must be >= ${AutoChartGenerator.MIN_HOLD_DURATION_MS}ms",
            holds.all { it.durationMs >= AutoChartGenerator.MIN_HOLD_DURATION_MS }
        )

        // Chorus drum hits should create adjacent double-tap pairs (abs(lane1 - lane2) == 1)
        val chorusByTime = chart.notes.filter { it.timeMs >= 8000L && it.type == NoteType.TAP }.groupBy { it.timeMs }
        val hasAdjacentDrumDouble = chorusByTime.values.any { group ->
            group.size >= 2 && kotlin.math.abs(group[0].lane - group[1].lane) == 1
        }
        assertTrue("Chorus kick drums should produce adjacent double-tap pairs", hasAdjacentDrumDouble)
    }

    @Test
    fun beatmapChartJsonRoundTripPreservesAllFourNoteTypes() {
        val path = listOf(
            SlidePoint(3000L, 0),
            SlidePoint(3400L, 0),
            SlidePoint(3470L, 2),
            SlidePoint(3900L, 2)
        )
        val original = BeatmapChart(
            songId = "editor_song",
            title = "Custom Track",
            artist = "Creator",
            bpm = 136,
            durationMs = 120_000L,
            laneCount = 5,
            difficulty = Difficulty.MASTER,
            onlyTapAndHold = false,
            notes = listOf(
                ChartNote(1, 500L, 0, NoteType.TAP),
                ChartNote(2, 1200L, 2, NoteType.HOLD, durationMs = 650L, slideTargetLane = 2),
                ChartNote(3, 2100L, 1, NoteType.SLIDE, slideTargetLane = 4),
                ChartNote(4, 3000L, 0, NoteType.HOLD_SLIDE, durationMs = 900L, slideTargetLane = 2, pathPoints = path)
            )
        )
        val restored = BeatmapChart.fromJson(original.toJson())
        assertEquals(original.bpm, restored.bpm)
        assertEquals(original.laneCount, restored.laneCount)
        assertEquals(original.notes.size, restored.notes.size)
        assertEquals(NoteType.SLIDE, restored.notes[2].type)
        assertEquals(4, restored.notes[2].slideTargetLane)
        assertEquals(NoteType.HOLD_SLIDE, restored.notes[3].type)
        assertEquals(4, restored.notes[3].pathPoints.size)
        assertEquals(3900L, restored.notes[3].endTimeMs)
    }
}
