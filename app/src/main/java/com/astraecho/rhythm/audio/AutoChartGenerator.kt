package com.astraecho.rhythm.audio

import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartConfig
import com.astraecho.rhythm.model.ChartNote
import com.astraecho.rhythm.model.NoteType
import com.astraecho.rhythm.model.SlidePoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Compact frame-by-frame acoustic features extracted from PCM audio.
 * Includes low-band drum/bass energy ratio for kick-drum adjacent-double detection
 * and chorus bass-hold suppression.
 */
data class AudioFeatureFrames(
    val hopDurationMs: Double,
    val durationMs: Long,
    val rmsEnergy: FloatArray,
    val spectralFlux: FloatArray,
    val lowBandRatio: FloatArray,
    val highBandRatio: FloatArray,
    val spectralCentroidNorm: FloatArray
) {
    fun writeToFile(file: File) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(MAGIC)
            out.writeDouble(hopDurationMs)
            out.writeLong(durationMs)
            out.writeInt(rmsEnergy.size)
            for (v in rmsEnergy) out.writeFloat(v)
            for (v in spectralFlux) out.writeFloat(v)
            for (v in lowBandRatio) out.writeFloat(v)
            for (v in highBandRatio) out.writeFloat(v)
            for (v in spectralCentroidNorm) out.writeFloat(v)
        }
    }

    companion object {
        private const val MAGIC = 0x41454633 // "AEF3"

        fun readFromFile(file: File): AudioFeatureFrames? {
            if (!file.exists() || file.length() < 24) return null
            return runCatching {
                DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                    if (inp.readInt() != MAGIC) return null
                    val hopMs = inp.readDouble()
                    val durMs = inp.readLong()
                    val n = inp.readInt()
                    if (n <= 0 || n > 200_000) return null
                    val rms = FloatArray(n) { inp.readFloat() }
                    val flux = FloatArray(n) { inp.readFloat() }
                    val low = FloatArray(n) { inp.readFloat() }
                    val high = FloatArray(n) { inp.readFloat() }
                    val cent = FloatArray(n) { inp.readFloat() }
                    AudioFeatureFrames(hopMs, durMs, rms, flux, low, high, cent)
                }
            }.getOrNull()
        }
    }
}

/**
 * Musical DSP analyzer and rhythm-game beatmap generator:
 * - Strict Hold duration threshold (>= 460ms real sustain — no artificial hold spam!)
 * - Calm sections welcome sustained bass/melody holds; intense choruses keep all lanes open for melody!
 * - Punchy kick/snare drum hits generate ADJACENT double-tap pairs ([0+1], [1+2], [2+3]) for tactile drum feel!
 * - Rich 4K/5K/6K pattern variety: staircases (0-1-2-3), trills (0-2-0-2), adjacent drum doubles,
 *   split chords, cross-lane slides, and balanced left/right hand load!
 */
object AutoChartGenerator {

    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 512
    const val MIN_HOLD_DURATION_MS = 460L

    private val hannWindow: FloatArray = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    fun analyzePcm(
        samples: FloatArray,
        sampleRate: Int,
        onProgress: ((Float) -> Unit)? = null
    ): AudioFeatureFrames {
        val totalFrames = max(1, (samples.size - FFT_SIZE) / HOP_SIZE + 1)
        val rmsEnergy = FloatArray(totalFrames)
        val spectralFlux = FloatArray(totalFrames)
        val lowBandRatio = FloatArray(totalFrames)
        val highBandRatio = FloatArray(totalFrames)
        val spectralCentroidNorm = FloatArray(totalFrames)

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val halfBins = FFT_SIZE / 2
        val prevLogMag = FloatArray(halfBins)

        val hzPerBin = sampleRate.toFloat() / FFT_SIZE
        val lowEndBin = ((260f / hzPerBin).toInt()).coerceIn(3, halfBins / 4)
        val highStartBin = ((3000f / hzPerBin).toInt()).coerceIn(lowEndBin + 4, halfBins - 1)
        val maxUsefulBin = ((8000f / hzPerBin).toInt()).coerceIn(highStartBin + 1, halfBins)

        var prevRms = 0f
        var smoothRms = 0.02f

        for (frame in 0 until totalFrames) {
            val offset = frame * HOP_SIZE
            var sumSq = 0f
            for (i in 0 until FFT_SIZE) {
                val s = if (offset + i < samples.size) samples[offset + i] else 0f
                sumSq += s * s
                re[i] = s * hannWindow[i]
                im[i] = 0f
            }
            val rms = sqrt(sumSq / FFT_SIZE)
            rmsEnergy[frame] = rms

            smoothRms = smoothRms * 0.92f + max(rms, 0.008f) * 0.08f
            val agcGain = (0.18f / max(smoothRms, 0.012f)).coerceIn(0.4f, 14.0f)

            fftInPlace(re, im)

            var logFlux = 0f
            var totalMag = 0f
            var lowMag = 0f
            var highMag = 0f
            var weightedBinSum = 0f

            for (b in 1 until halfBins) {
                val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                val logMag = ln(1f + 16f * mag * agcGain)
                val diff = logMag - prevLogMag[b]
                if (diff > 0f) logFlux += diff
                prevLogMag[b] = logMag

                if (b < maxUsefulBin) {
                    totalMag += mag
                    weightedBinSum += b * mag
                    if (b <= lowEndBin) lowMag += mag
                    if (b >= highStartBin) highMag += mag
                }
            }

            val relJump = (max(0f, rms - prevRms) / max(smoothRms, 0.012f)) * 28f
            prevRms = rms

            spectralFlux[frame] = logFlux + relJump
            lowBandRatio[frame] = if (totalMag > 1e-5f) (lowMag / totalMag).coerceIn(0f, 1f) else 0f
            highBandRatio[frame] = if (totalMag > 1e-5f) (highMag / totalMag).coerceIn(0f, 1f) else 0f
            val centroidBin = if (totalMag > 1e-5f) weightedBinSum / totalMag else (maxUsefulBin * 0.5f)
            spectralCentroidNorm[frame] = (centroidBin / maxUsefulBin.toFloat()).coerceIn(0f, 1f)

            if (frame % 64 == 0) {
                onProgress?.invoke(frame.toFloat() / totalFrames)
            }
        }
        onProgress?.invoke(1f)

        val hopMs = (HOP_SIZE.toDouble() / sampleRate.toDouble()) * 1000.0
        val durationMs = ((samples.size.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()
        return AudioFeatureFrames(
            hopDurationMs = hopMs,
            durationMs = durationMs,
            rmsEnergy = rmsEnergy,
            spectralFlux = spectralFlux,
            lowBandRatio = lowBandRatio,
            highBandRatio = highBandRatio,
            spectralCentroidNorm = spectralCentroidNorm
        )
    }

    private enum class PatternMode {
        MELODY_FLOW,
        STAIRCASE_RIGHT,
        STAIRCASE_LEFT,
        TRILL
    }

    fun generateChart(
        songId: String,
        title: String,
        artist: String,
        features: AudioFeatureFrames,
        config: ChartConfig
    ): BeatmapChart {
        val lanes = config.clampedLanes
        val diff = config.difficulty
        val flux = features.spectralFlux
        val energy = features.rmsEnergy
        val n = flux.size

        if (n < 8) {
            return BeatmapChart(
                songId = songId,
                title = title,
                artist = artist,
                bpm = 120,
                durationMs = features.durationMs,
                laneCount = lanes,
                difficulty = diff,
                onlyTapAndHold = config.onlyTapAndHold,
                notes = emptyList()
            )
        }

        val estimatedBpm = estimateBpm(flux, features.hopDurationMs)
        val beatMs = (60_000.0 / estimatedBpm).toLong().coerceIn(310L, 860L)

        // Section intensity (0..1) using local RMS vs song's 75th-percentile energy
        // (using p75 instead of p90 ensures punchy staccato drum choruses read as high intensity, not calm!)
        val sortedEnergy = energy.copyOf()
        java.util.Arrays.sort(sortedEnergy)
        val p75Energy = sortedEnergy[((n - 1) * 0.75f).toInt().coerceIn(0, n - 1)].coerceAtLeast(1e-4f)
        val intensityHalfWin = max(6, (750.0 / features.hopDurationMs).roundToInt())
        val sectionIntensity = FloatArray(n)
        var runningSum = 0f
        var winCount = 0
        for (k in 0 until min(n, intensityHalfWin)) {
            runningSum += energy[k]
            winCount++
        }
        for (i in 0 until n) {
            val addIdx = i + intensityHalfWin
            if (addIdx < n) {
                runningSum += energy[addIdx]
                winCount++
            }
            val remIdx = i - intensityHalfWin - 1
            if (remIdx >= 0) {
                runningSum -= energy[remIdx]
                winCount--
            }
            sectionIntensity[i] = ((runningSum / max(1, winCount)) / p75Energy).coerceIn(0f, 1f)
        }

        val backFrames = max(5, (480.0 / features.hopDurationMs).roundToInt())
        val aheadFrames = max(3, (220.0 / features.hopDurationMs).roundToInt())
        val globalMeanEnergy = energy.average().toFloat().coerceAtLeast(1e-4f)
        val silenceFloor = min(0.006f, globalMeanEnergy * 0.035f).coerceAtLeast(0.0015f)
        val windowBuf = FloatArray(backFrames + aheadFrames + 2)

        data class RawOnset(
            val frame: Int,
            val timeMs: Long,
            val strength: Float,
            val lowRatio: Float,
            val highRatio: Float,
            val centroid: Float,
            val intensity: Float
        )

        val rawOnsets = mutableListOf<RawOnset>()
        var lastOnsetTime = -10_000L

        // 1. Guarantee capture of the very first audible note
        val firstSoundFrame = (1 until n - 2).firstOrNull { i ->
            energy[i] >= silenceFloor * 1.8f && flux[i] >= 1.5f
        }
        if (firstSoundFrame != null) {
            var peakFrame: Int = firstSoundFrame
            for (k in firstSoundFrame..min(n - 2, firstSoundFrame + 4)) {
                if (flux[k] > flux[peakFrame]) peakFrame = k
            }
            val firstTimeMs = max(60L, (peakFrame * features.hopDurationMs).toLong())
            if (firstTimeMs < features.durationMs - 500L) {
                rawOnsets.add(
                    RawOnset(
                        frame = peakFrame,
                        timeMs = firstTimeMs,
                        strength = 1.6f,
                        lowRatio = features.lowBandRatio[peakFrame],
                        highRatio = features.highBandRatio[peakFrame],
                        centroid = features.spectralCentroidNorm[peakFrame],
                        intensity = sectionIntensity[peakFrame]
                    )
                )
                lastOnsetTime = firstTimeMs
            }
        }

        // 2. Intensity-aware adaptive onset peak picking
        for (i in 1 until n - 1) {
            val timeMs = (i * features.hopDurationMs).toLong()
            if (timeMs < 60L || timeMs > features.durationMs - 400L) continue

            val intensity = sectionIntensity[i]
            val calmFactor = ((0.65f - intensity) / 0.50f).coerceIn(0f, 1f)
            val dynamicMinIoiMs = (diff.minIoiMs * (1f + calmFactor * 0.72f)).toLong()
            if (timeMs - lastOnsetTime < dynamicMinIoiMs) continue

            val v = flux[i]
            if (v < 1.2f) continue
            if (v < flux[i - 1] || v < flux[i + 1]) continue
            if (v <= flux[i - 1] && v <= flux[i + 1]) continue
            if (energy[i] < silenceFloor) continue

            val start = max(0, i - backFrames)
            val end = min(n - 1, i + aheadFrames)
            var count = 0
            for (k in start..end) windowBuf[count++] = flux[k]
            java.util.Arrays.sort(windowBuf, 0, count)
            val localMedian = windowBuf[count / 2].coerceAtLeast(0.8f)

            val dynamicMultiplier = diff.thresholdMultiplier + calmFactor * 0.28f
            val adaptiveThreshold = localMedian * dynamicMultiplier + 1.15f
            val secondaryPulse = (diff.ordinal >= 2) &&
                intensity >= 0.58f &&
                v >= localMedian * (diff.thresholdMultiplier * 0.88f) &&
                (timeMs - lastOnsetTime) >= (diff.minIoiMs * 1.35f)

            if (v >= adaptiveThreshold || secondaryPulse) {
                rawOnsets.add(
                    RawOnset(
                        frame = i,
                        timeMs = timeMs,
                        strength = v / localMedian,
                        lowRatio = features.lowBandRatio[i],
                        highRatio = features.highBandRatio[i],
                        centroid = features.spectralCentroidNorm[i],
                        intensity = intensity
                    )
                )
                lastOnsetTime = timeMs
            }
        }

        val seed = (songId.hashCode() * 31 + lanes * 17 + diff.ordinal * 7 + if (config.onlyTapAndHold) 1 else 0)
        val rng = Random(seed)

        val notes = mutableListOf<ChartNote>()
        val laneBusyUntilMs = LongArray(lanes) { -1000L }
        val laneHoldUntilMs = LongArray(lanes) { -1000L }
        val laneUsageCount = IntArray(lanes)
        var lastLane = lanes / 2
        var noteId = 1

        // Pattern motif state for varied, fun 4K/5K/6K choreography (staircases, trills, streams)
        var patternMode = PatternMode.MELODY_FLOW
        var patternRemaining = 0
        var trillLaneA = 0
        var trillLaneB = min(2, lanes - 1)

        // Normalize onset spectral centroids to song's 5th..95th percentile
        val sortedCentroids = FloatArray(rawOnsets.size) { rawOnsets[it].centroid }
        java.util.Arrays.sort(sortedCentroids)
        val cMin = if (sortedCentroids.isNotEmpty()) sortedCentroids[(sortedCentroids.size * 0.05f).toInt()] else 0f
        val cMax = if (sortedCentroids.isNotEmpty()) sortedCentroids[(sortedCentroids.size * 0.95f).toInt()] else 1f
        val cSpan = (cMax - cMin).coerceAtLeast(0.05f)

        for (idx in rawOnsets.indices) {
            val onset = rawOnsets[idx]
            val remainingSongMs = features.durationMs - 400L - onset.timeMs
            if (remainingSongMs < 140L) continue

            val prevIoiMs = if (idx > 0) onset.timeMs - rawOnsets[idx - 1].timeMs else 500L
            val isChorus = onset.intensity >= 0.68f
            val isCalm = onset.intensity < 0.58f
            val isDrumHit = (onset.lowRatio >= 0.34f && onset.strength >= 1.38f) ||
                (onset.lowRatio >= 0.28f && onset.strength >= 1.72f && onset.intensity >= 0.50f)

            // Refresh choreography pattern periodically for rich variety (no monotonous single-lane spam!)
            if (patternRemaining <= 0) {
                if (prevIoiMs <= 230L && onset.intensity >= 0.55f && rng.nextFloat() < 0.42f) {
                    val roll = rng.nextFloat()
                    when {
                        roll < 0.36f -> {
                            patternMode = PatternMode.STAIRCASE_RIGHT
                            patternRemaining = lanes
                        }
                        roll < 0.72f -> {
                            patternMode = PatternMode.STAIRCASE_LEFT
                            patternRemaining = lanes
                        }
                        else -> {
                            patternMode = PatternMode.TRILL
                            patternRemaining = 4
                            // Pick balanced left/right trill pair
                            val leftL = rng.nextInt(lanes / 2)
                            val rightL = (lanes / 2) + rng.nextInt(lanes - lanes / 2)
                            trillLaneA = leftL
                            trillLaneB = rightL
                        }
                    }
                } else {
                    patternMode = PatternMode.MELODY_FLOW
                    patternRemaining = 2
                }
            }

            val normPitch = ((onset.centroid - cMin) / cSpan).coerceIn(0f, 0.999f)
            val preferredLane = when (patternMode) {
                PatternMode.STAIRCASE_RIGHT -> (lastLane + 1) % lanes
                PatternMode.STAIRCASE_LEFT -> (lastLane - 1 + lanes) % lanes
                PatternMode.TRILL -> if (lastLane == trillLaneA) trillLaneB else trillLaneA
                PatternMode.MELODY_FLOW -> (normPitch * lanes).toInt().coerceIn(0, lanes - 1)
            }
            patternRemaining--

            val lane = pickAvailableLane(
                preferred = preferredLane,
                lastLane = lastLane,
                lanes = lanes,
                timeMs = onset.timeMs,
                laneBusyUntilMs = laneBusyUntilMs,
                laneUsageCount = laneUsageCount,
                strictPattern = (patternMode != PatternMode.MELODY_FLOW),
                rng = rng
            ) ?: continue

            val activeHoldsNow = laneHoldUntilMs.count { it > onset.timeMs }
            val sustainMs = measureMusicalSustainMs(
                startFrame = onset.frame,
                energy = energy,
                globalMeanEnergy = globalMeanEnergy,
                hopMs = features.hopDurationMs,
                maxDurationMs = min(1350L, remainingSongMs)
            )

            // STRICT HOLD STANDARD:
            // 1. Must genuinely sustain >= MIN_HOLD_DURATION_MS (460ms)
            // 2. In intense choruses: NEVER place bass holds, and keep melody holds very rare so all 4 lanes stay free!
            // 3. In calm sections: welcome sustained bass/melody holds cleanly!
            val allowHoldHere = when {
                sustainMs < MIN_HOLD_DURATION_MS -> false
                activeHoldsNow >= 1 -> false // Never crowd track with multiple simultaneous holds
                isChorus -> !isDrumHit && onset.lowRatio < 0.32f && sustainMs >= 640L && rng.nextFloat() < 0.08f
                isCalm -> rng.nextFloat() < (if (config.onlyTapAndHold) diff.holdChance * 1.35f else diff.holdChance * 1.15f)
                else -> !isDrumHit && sustainMs >= 540L && rng.nextFloat() < (diff.holdChance * 0.45f)
            }

            // Cross-lane slides shine in medium & intense sections on bright high-frequency accents
            val effectiveSlideChance = when {
                isCalm -> diff.slideChance * 0.35f
                isChorus -> diff.slideChance * 1.25f
                else -> diff.slideChance * 0.90f
            }
            val wantSlide = !config.onlyTapAndHold &&
                !allowHoldHere &&
                !isDrumHit &&
                idx > 0 &&
                (onset.highRatio > 0.24f || onset.strength > 1.55f) &&
                rng.nextFloat() < effectiveSlideChance

            if (allowHoldHere) {
                val subBeat = max(160L, beatMs / 2)
                val baseDur = ((sustainMs / subBeat) * subBeat)
                    .coerceIn(MIN_HOLD_DURATION_MS, 1280L)
                    .coerceAtMost(remainingSongMs)

                val makeHoldSlide = !config.onlyTapAndHold &&
                    !isCalm &&
                    baseDur >= 560L &&
                    rng.nextFloat() < (0.35f + diff.slideChance * 0.6f)

                val holdEnd = onset.timeMs + baseDur
                if (makeHoldSlide) {
                    val path = buildHoldSlidePath(
                        startTimeMs = onset.timeMs,
                        startLane = lane,
                        totalDurationMs = baseDur,
                        lanes = lanes,
                        laneBusyUntilMs = laneBusyUntilMs,
                        rng = rng
                    )
                    if (path.size >= 3) {
                        val endLane = path.last().lane
                        val endTime = path.last().timeMs
                        notes.add(
                            ChartNote(
                                id = noteId++,
                                timeMs = onset.timeMs,
                                lane = lane,
                                type = NoteType.HOLD_SLIDE,
                                durationMs = endTime - onset.timeMs,
                                slideTargetLane = endLane,
                                pathPoints = path
                            )
                        )
                        for (pt in path) {
                            laneBusyUntilMs[pt.lane] = max(laneBusyUntilMs[pt.lane], endTime + 110L)
                            laneHoldUntilMs[pt.lane] = max(laneHoldUntilMs[pt.lane], endTime)
                        }
                        laneUsageCount[lane]++
                        laneUsageCount[endLane]++
                        lastLane = endLane
                    } else {
                        notes.add(ChartNote(noteId++, onset.timeMs, lane, NoteType.HOLD, baseDur, lane))
                        laneBusyUntilMs[lane] = holdEnd + 110L
                        laneHoldUntilMs[lane] = holdEnd
                        laneUsageCount[lane]++
                        lastLane = lane
                    }
                } else {
                    notes.add(ChartNote(noteId++, onset.timeMs, lane, NoteType.HOLD, baseDur, lane))
                    laneBusyUntilMs[lane] = holdEnd + 110L
                    laneHoldUntilMs[lane] = holdEnd
                    laneUsageCount[lane]++
                    lastLane = lane
                }

                // Pair strong main-melody accents with a crisp single TAP on a free lane
                if (onset.strength >= 1.52f && !isCalm) {
                    val melodyLane = (0 until lanes)
                        .filter { it != lane && laneBusyUntilMs[it] <= onset.timeMs }
                        .minByOrNull { laneUsageCount[it] }
                    if (melodyLane != null) {
                        notes.add(ChartNote(noteId++, onset.timeMs, melodyLane, NoteType.TAP, 0L, melodyLane))
                        laneBusyUntilMs[melodyLane] = onset.timeMs + 95L
                        laneUsageCount[melodyLane]++
                    }
                }
                continue
            }

            if (wantSlide) {
                val targetLane = pickCrossSlideTarget(lane, lanes, onset.timeMs, laneBusyUntilMs, rng)
                if (targetLane != null && targetLane != lane) {
                    notes.add(ChartNote(noteId++, onset.timeMs, lane, NoteType.SLIDE, 0L, targetLane))
                    val lo = min(lane, targetLane)
                    val hi = max(lane, targetLane)
                    for (l in lo..hi) laneBusyUntilMs[l] = max(laneBusyUntilMs[l], onset.timeMs + 130L)
                    laneUsageCount[lane]++
                    laneUsageCount[targetLane]++
                    lastLane = targetLane
                    continue
                }
            }

            // Place primary TAP note
            notes.add(ChartNote(noteId++, onset.timeMs, lane, NoteType.TAP, 0L, lane))
            laneBusyUntilMs[lane] = onset.timeMs + 90L
            laneUsageCount[lane]++
            lastLane = lane

            // DRUM HIT -> ADJACENT DOUBLE-TAP ([lane, lane+1] or [lane-1, lane]) for authentic drum impact!
            val drumDoubleChance = when {
                onset.intensity < 0.42f -> 0f
                isChorus -> (diff.chordChance + 0.34f).coerceAtMost(0.82f)
                else -> (diff.chordChance + 0.16f).coerceAtMost(0.60f)
            }
            if (isDrumHit && rng.nextFloat() < drumDoubleChance) {
                val adjCandidates = listOf(lane - 1, lane + 1)
                    .filter { it in 0 until lanes && laneBusyUntilMs[it] <= onset.timeMs }
                val adjLane = adjCandidates.minByOrNull { laneUsageCount[it] }
                if (adjLane != null) {
                    notes.add(ChartNote(noteId++, onset.timeMs, adjLane, NoteType.TAP, 0L, adjLane))
                    laneBusyUntilMs[adjLane] = onset.timeMs + 90L
                    laneUsageCount[adjLane]++
                    continue
                }
            }

            // Non-drum strong harmonic accents in chorus/medium -> split-hand double-tap (e.g. 0+3 or 0+2)
            val splitChordChance = when {
                isCalm -> 0f
                isChorus -> diff.chordChance * 0.85f
                else -> diff.chordChance * 0.40f
            }
            if (!isDrumHit && onset.strength > 1.55f && rng.nextFloat() < splitChordChance) {
                val mirrorLane = lanes - 1 - lane
                val secondLane = (0 until lanes)
                    .filter { it != lane && laneBusyUntilMs[it] <= onset.timeMs }
                    .minByOrNull { cand ->
                        laneUsageCount[cand] * 1.5f + (if (cand == mirrorLane) -0.8f else 0f)
                    }
                if (secondLane != null) {
                    notes.add(ChartNote(noteId++, onset.timeMs, secondLane, NoteType.TAP, 0L, secondLane))
                    laneBusyUntilMs[secondLane] = onset.timeMs + 90L
                    laneUsageCount[secondLane]++
                }
            }
        }

        notes.sortWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })

        return BeatmapChart(
            songId = songId,
            title = title,
            artist = artist,
            bpm = estimatedBpm,
            durationMs = features.durationMs,
            laneCount = lanes,
            difficulty = diff,
            onlyTapAndHold = config.onlyTapAndHold,
            notes = notes
        )
    }

    private fun buildHoldSlidePath(
        startTimeMs: Long,
        startLane: Int,
        totalDurationMs: Long,
        lanes: Int,
        laneBusyUntilMs: LongArray,
        rng: Random
    ): List<SlidePoint> {
        val nextLane = pickCrossSlideTarget(startLane, lanes, startTimeMs, laneBusyUntilMs, rng, maxSpan = 1)
            ?: return emptyList()

        val points = mutableListOf<SlidePoint>()
        points.add(SlidePoint(startTimeMs, startLane))

        val midTime1 = startTimeMs + (totalDurationMs * 0.45f).toLong()
        val slideStepMs = 70L
        points.add(SlidePoint(midTime1, startLane))
        points.add(SlidePoint(midTime1 + slideStepMs, nextLane))

        if (totalDurationMs >= 880L && rng.nextFloat() < 0.5f) {
            val midTime2 = startTimeMs + (totalDurationMs * 0.78f).toLong()
            val thirdLane = if (rng.nextFloat() < 0.65f) startLane else {
                pickCrossSlideTarget(nextLane, lanes, midTime2, laneBusyUntilMs, rng, maxSpan = 1) ?: startLane
            }
            points.add(SlidePoint(midTime2, nextLane))
            points.add(SlidePoint(midTime2 + slideStepMs, thirdLane))
            points.add(SlidePoint(startTimeMs + totalDurationMs, thirdLane))
        } else {
            points.add(SlidePoint(startTimeMs + totalDurationMs, nextLane))
        }
        return points
    }

    private fun pickCrossSlideTarget(
        startLane: Int,
        lanes: Int,
        timeMs: Long,
        laneBusyUntilMs: LongArray,
        rng: Random,
        maxSpan: Int = 2
    ): Int? {
        val candidates = mutableListOf<Int>()
        for (delta in 1..maxSpan) {
            val right = startLane + delta
            if (right < lanes && (startLane + 1..right).all { laneBusyUntilMs[it] <= timeMs }) {
                candidates.add(right)
            }
            val left = startLane - delta
            if (left >= 0 && (left until startLane).all { laneBusyUntilMs[it] <= timeMs }) {
                candidates.add(left)
            }
        }
        if (candidates.isEmpty()) return null
        val oneLane = candidates.filter { abs(it - startLane) == 1 }
        return if (oneLane.isNotEmpty() && rng.nextFloat() < 0.72f) {
            oneLane[rng.nextInt(oneLane.size)]
        } else {
            candidates[rng.nextInt(candidates.size)]
        }
    }

    private fun pickAvailableLane(
        preferred: Int,
        lastLane: Int,
        lanes: Int,
        timeMs: Long,
        laneBusyUntilMs: LongArray,
        laneUsageCount: IntArray,
        strictPattern: Boolean,
        rng: Random
    ): Int? {
        val free = (0 until lanes).filter { laneBusyUntilMs[it] <= timeMs }
        if (free.isEmpty()) return null

        // For staircases & trills, follow the exact choreography lane if it's free!
        if (strictPattern && preferred in free && preferred != lastLane) {
            return preferred
        }

        val nonRepeat = free.filter { it != lastLane }
        val pool = if (nonRepeat.isNotEmpty() && rng.nextFloat() < 0.85f) nonRepeat else free

        val avgUsage = laneUsageCount.average().toFloat()
        val mid = lanes / 2
        var leftTotal = 0
        var rightTotal = 0
        for (l in 0 until lanes) {
            if (l < mid) leftTotal += laneUsageCount[l] else rightTotal += laneUsageCount[l]
        }
        val handBias = (leftTotal - rightTotal).toFloat()

        return pool.minByOrNull { cand ->
            val pitchCost = abs(cand - preferred) * 0.55f
            val usagePenalty = (laneUsageCount[cand] - avgUsage) * 1.45f
            val handPenalty = if (cand < mid) handBias * 0.28f else -handBias * 0.28f
            val jitter = rng.nextFloat() * 0.65f
            pitchCost + usagePenalty + handPenalty + jitter
        }
    }

    private fun measureMusicalSustainMs(
        startFrame: Int,
        energy: FloatArray,
        globalMeanEnergy: Float,
        hopMs: Double,
        maxDurationMs: Long
    ): Long {
        if (maxDurationMs < MIN_HOLD_DURATION_MS) return 0L
        val peak = energy[startFrame].coerceAtLeast(1e-4f)
        // Require genuinely sustained energy (>= 42% of onset peak, not just faint reverb tail)
        val threshold = max(peak * 0.42f, globalMeanEnergy * 0.35f)
        val maxFrames = (maxDurationMs / hopMs).toInt()
        var sustained = 0
        var lowStreak = 0
        for (k in 1..maxFrames) {
            val idx = startFrame + k
            if (idx >= energy.size) break
            if (energy[idx] >= threshold) {
                sustained = k
                lowStreak = 0
            } else {
                lowStreak++
                if (lowStreak >= 2) break
            }
        }
        return (sustained * hopMs).toLong()
    }

    private fun estimateBpm(flux: FloatArray, hopMs: Double): Int {
        val minBpm = 72
        val maxBpm = 184
        val minLag = max(1, ((60_000.0 / maxBpm) / hopMs).roundToInt())
        val maxLag = min(flux.size / 3, ((60_000.0 / minBpm) / hopMs).roundToInt())
        if (maxLag <= minLag) return 120

        val sampleLimit = min(flux.size, 1800)
        var bestLag = minLag
        var bestScore = -1.0

        for (lag in minLag..maxLag) {
            var corr = 0.0
            val count = sampleLimit - lag
            if (count <= 0) continue
            for (i in 0 until count) {
                corr += flux[i] * flux[i + lag]
            }
            val score = corr / count
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        val bpm = (60_000.0 / (bestLag * hopMs)).roundToInt()
        return bpm.coerceIn(72, 184)
    }

    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
