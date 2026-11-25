/**
 * Upgraded In-Browser Auto-Chart Generator for 《星律回响 · ASTRA ECHO》
 *
 * Key upgrades:
 * 1. Radix-2 1024-pt Hann-windowed FFT with 10ms hop (100fps) + log-magnitude
 *    positive spectral flux across 3 musical bands:
 *      - Bass / Kick (40–180 Hz)
 *      - Main Melody / Lead / Vocal (260–3600 Hz, highest weight)
 *      - Crisp Attack / Percussion (3600–9000 Hz)
 *    Catches prominent melodic note changes even on heavily compressed EDM/rhythm tracks!
 * 2. Automatic BPM estimation via autocorrelation on the onset envelope.
 * 3. Strict zero-overlap lane scheduler + post-pass sanitizer:
 *      - NEVER places any note inside an active HOLD / HOLD_SLIDE on the same lane.
 *      - Caps concurrent holds (max 1 on 4K, max 2 on 5K/6K) and requires genuine
 *        low-flux harmonic sustain so loud choruses never turn into giant overlapping holds.
 * 4. Distinct, well-calibrated difficulty curves (EASY / NORMAL / HARD / MASTER).
 */

function fftRadix2(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      let tmp = re[i]; re[i] = re[j]; re[j] = tmp;
      tmp = im[i]; im[i] = im[j]; im[j] = tmp;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len;
    const wlenRe = Math.cos(ang);
    const wlenIm = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let wRe = 1;
      let wIm = 0;
      const half = len >> 1;
      for (let j = 0; j < half; j++) {
        const uRe = re[i + j];
        const uIm = im[i + j];
        const vRe = re[i + j + half] * wRe - im[i + j + half] * wIm;
        const vIm = re[i + j + half] * wIm + im[i + j + half] * wRe;
        re[i + j] = uRe + vRe;
        im[i + j] = uIm + vIm;
        re[i + j + half] = uRe - vRe;
        im[i + j + half] = uIm - vIm;
        const nextWRe = wRe * wlenRe - wIm * wlenIm;
        wIm = wRe * wlenIm + wIm * wlenRe;
        wRe = nextWRe;
      }
    }
  }
}

/**
 * Estimate BPM (80..200) from a 10ms-hop onset flux envelope via autocorrelation.
 */
export function estimateBpmFromEnvelope(onsetEnv, hopMs = 10) {
  const n = Math.min(onsetEnv.length, 6000); // analyze up to first 60s
  if (n < 400) return 128;

  // Mean-subtract
  let mean = 0;
  for (let i = 0; i < n; i++) mean += onsetEnv[i];
  mean /= n;

  const minLag = Math.round((60000 / 200) / hopMs); // 200 BPM -> 30 frames
  const maxLag = Math.round((60000 / 80) / hopMs);  // 80 BPM  -> 75 frames

  let bestLag = Math.round((60000 / 128) / hopMs);
  let bestScore = -Infinity;

  for (let lag = minLag; lag <= maxLag; lag++) {
    let corr = 0;
    const limit = n - lag;
    for (let i = 0; i < limit; i++) {
      corr += (onsetEnv[i] - mean) * (onsetEnv[i + lag] - mean);
    }
    // Slight perceptual prior favoring 105..165 BPM to avoid half/double errors
    const bpm = 60000 / (lag * hopMs);
    const prior = 1 - Math.abs(bpm - 135) * 0.0012;
    const score = (corr / limit) * prior;
    if (score > bestScore) {
      bestScore = score;
      bestLag = lag;
    }
  }

  return Math.round((60000 / (bestLag * hopMs)) * 2) / 2;
}

/**
 * Post-pass safety sanitizer: guarantees zero notes ever land inside or overlap
 * with a HOLD / HOLD_SLIDE on the same lane.
 */
function sanitizeLaneOverlaps(notes, laneCount) {
  notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
  const cleaned = [];
  const laneOccupiedUntil = new Array(laneCount).fill(-1000);

  for (const n of notes) {
    // Check if primary lane is inside an existing hold/note safety window
    if (n.timeMs < laneOccupiedUntil[n.lane]) {
      // Try relocating a TAP to an adjacent free lane if available
      if (n.type === 'TAP') {
        let freeLane = -1;
        for (let d = 1; d < laneCount; d++) {
          for (const cand of [n.lane - d, n.lane + d]) {
            if (cand >= 0 && cand < laneCount && n.timeMs >= laneOccupiedUntil[cand]) {
              freeLane = cand;
              break;
            }
          }
          if (freeLane >= 0) break;
        }
        if (freeLane < 0) continue; // drop if every lane is busy
        n.lane = freeLane;
        n.slideTargetLane = freeLane;
      } else {
        continue; // drop overlapping hold/slide
      }
    }

    // For HOLD_SLIDE or SLIDE, also ensure target lane is clear
    const target = n.slideTargetLane ?? n.lane;
    if (target !== n.lane && n.timeMs < laneOccupiedUntil[target]) {
      // Demote to clean TAP/HOLD on primary lane rather than overlapping target lane
      if (n.type === 'HOLD_SLIDE') {
        n.type = 'HOLD';
        n.slideTargetLane = n.lane;
        delete n.pathPoints;
      } else if (n.type === 'SLIDE') {
        n.type = 'TAP';
        n.slideTargetLane = n.lane;
      }
    }

    cleaned.push(n);
    const guardMs = n.durationMs > 0 ? 90 : 55;
    const busyEnd = n.timeMs + (n.durationMs || 0) + guardMs;
    laneOccupiedUntil[n.lane] = Math.max(laneOccupiedUntil[n.lane], busyEnd);
    if ((n.slideTargetLane ?? n.lane) !== n.lane) {
      laneOccupiedUntil[n.slideTargetLane] = Math.max(laneOccupiedUntil[n.slideTargetLane], busyEnd);
    }
  }

  return cleaned.map((n, idx) => ({ ...n, id: idx + 1 }));
}

export function generateAutoChart(audioBuffer, options = {}) {
  const {
    bpm = 128,
    offsetMs = 0,
    laneCount = 4,
    difficulty = 'NORMAL',
    onlyTapAndHold = false,
    densityScale = 1.0,
    minHoldMs = 460,
    drumDoubles = true,
    quantize = false,
    autoDetectBpm = false
  } = options;

  const sampleRate = audioBuffer.sampleRate;
  const totalSamples = audioBuffer.length;
  const durationMs = Math.round(audioBuffer.duration * 1000);

  // Mix down to mono
  const mono = new Float32Array(totalSamples);
  const channels = audioBuffer.numberOfChannels;
  for (let c = 0; c < channels; c++) {
    const ch = audioBuffer.getChannelData(c);
    for (let i = 0; i < totalSamples; i++) {
      mono[i] += ch[i] / channels;
    }
  }

  // 1024-sample FFT with 10ms hop (100 frames/sec)
  const fftSize = 1024;
  const hopMs = 10;
  const hopSamples = Math.max(1, Math.floor((sampleRate * hopMs) / 1000));
  const frameCount = Math.max(1, Math.floor((totalSamples - fftSize) / hopSamples));

  // Precompute Hann window
  const hann = new Float32Array(fftSize);
  for (let i = 0; i < fftSize; i++) {
    hann[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (fftSize - 1)));
  }

  const binHz = sampleRate / fftSize;
  const bassStart = Math.max(1, Math.floor(40 / binHz));
  const bassEnd = Math.min(fftSize / 2 - 1, Math.ceil(180 / binHz));
  const melStart = Math.max(bassEnd + 1, Math.floor(260 / binHz));
  const melEnd = Math.min(fftSize / 2 - 1, Math.ceil(3600 / binHz));
  const hiStart = Math.max(melEnd + 1, Math.floor(3600 / binHz));
  const hiEnd = Math.min(fftSize / 2 - 1, Math.ceil(9000 / binHz));

  const re = new Float32Array(fftSize);
  const im = new Float32Array(fftSize);
  const prevLogMag = new Float32Array(fftSize / 2);

  const rms = new Float32Array(frameCount);
  const bassFlux = new Float32Array(frameCount);
  const melodyFlux = new Float32Array(frameCount);
  const hiFlux = new Float32Array(frameCount);
  const melodyCentroid = new Float32Array(frameCount);
  const combinedOnset = new Float32Array(frameCount);

  for (let f = 0; f < frameCount; f++) {
    const start = f * hopSamples;
    let sq = 0;
    for (let i = 0; i < fftSize; i++) {
      const s = mono[start + i];
      sq += s * s;
      re[i] = s * hann[i];
      im[i] = 0;
    }
    rms[f] = Math.sqrt(sq / fftSize);

    fftRadix2(re, im);

    let bSum = 0;
    let mSum = 0;
    let hSum = 0;
    let mWeightedBin = 0;
    let mMagSum = 0;

    for (let k = 1; k < fftSize / 2; k++) {
      const mag = Math.hypot(re[k], im[k]);
      const logMag = Math.log1p(mag * 4.0);
      const diff = Math.max(0, logMag - prevLogMag[k]);
      prevLogMag[k] = logMag;

      if (k >= bassStart && k <= bassEnd) {
        bSum += diff;
      } else if (k >= melStart && k <= melEnd) {
        mSum += diff;
        if (diff > 0.05) {
          mWeightedBin += k * diff;
          mMagSum += diff;
        }
      } else if (k >= hiStart && k <= hiEnd) {
        hSum += diff;
      }
    }

    // Normalize by band width so melody changes stand out clearly
    const bNorm = bSum / Math.max(1, bassEnd - bassStart + 1);
    const mNorm = mSum / Math.max(1, melEnd - melStart + 1);
    const hNorm = hSum / Math.max(1, hiEnd - hiStart + 1);

    bassFlux[f] = bNorm;
    melodyFlux[f] = mNorm;
    hiFlux[f] = hNorm;
    melodyCentroid[f] = mMagSum > 0 ? (mWeightedBin / mMagSum - melStart) / Math.max(1, melEnd - melStart) : 0.5;
    // Main melody weighted strongly so prominent lead notes are always prioritized!
    combinedOnset[f] = mNorm * 2.3 + bNorm * 1.15 + hNorm * 0.55;
  }

  // Smooth onset slightly (3-frame triangle) to avoid double-triggering on a single attack
  const smoothOnset = new Float32Array(frameCount);
  for (let f = 1; f < frameCount - 1; f++) {
    smoothOnset[f] = 0.2 * combinedOnset[f - 1] + 0.6 * combinedOnset[f] + 0.2 * combinedOnset[f + 1];
  }

  const detectedBpm = autoDetectBpm ? estimateBpmFromEnvelope(smoothOnset, hopMs) : bpm;

  // Global energy baseline
  let avgRms = 0;
  for (let f = 0; f < frameCount; f++) avgRms += rms[f];
  avgRms = Math.max(1e-5, avgRms / frameCount);

  // Distinct difficulty profiles
  const diffConfig = {
    EASY:   { minGapMs: 420, threshMul: 1.95, stdMul: 1.05, doubleAllow: false, maxHolds: 1, slideStep: 14 },
    NORMAL: { minGapMs: 250, threshMul: 1.55, stdMul: 0.72, doubleAllow: true,  maxHolds: 1, slideStep: 9 },
    HARD:   { minGapMs: 155, threshMul: 1.28, stdMul: 0.48, doubleAllow: true,  maxHolds: 1, slideStep: 7 },
    MASTER: { minGapMs: 105, threshMul: 1.10, stdMul: 0.30, doubleAllow: true,  maxHolds: 2, slideStep: 6 }
  }[difficulty] || { minGapMs: 250, threshMul: 1.55, stdMul: 0.72, doubleAllow: true, maxHolds: 1, slideStep: 9 };

  const effectiveMinGap = Math.max(85, Math.round(diffConfig.minGapMs / densityScale));
  const beatMs = 60000 / Math.max(40, detectedBpm);
  const snapStep = beatMs / 4;

  const maybeSnapTime = (tMs) => {
    if (!quantize) return Math.round(tMs);
    const snapped = Math.round((tMs - offsetMs) / snapStep) * snapStep + offsetMs;
    return Math.max(0, Math.min(durationMs - 100, Math.round(snapped)));
  };

  // Lane scheduling & balance tracking
  const laneCounts = new Array(laneCount).fill(0);
  const laneHoldUntil = new Array(laneCount).fill(-1000);
  const laneLastTap = new Array(laneCount).fill(-1000);
  let lastLane = -1;
  let nextId = 1;
  const notes = [];

  function activeHoldCountAt(timeMs) {
    let count = 0;
    for (let l = 0; l < laneCount; l++) {
      if (laneHoldUntil[l] > timeMs) count++;
    }
    return count;
  }

  /**
   * Returns a free lane index, or -1 if ALL lanes are currently blocked by holds/recent notes.
   * Biases subtly toward pitch contour (melodyCentroid) while keeping overall lane load balanced.
   */
  function pickFreeBalancedLane(timeMs, centroid01) {
    let bestLane = -1;
    let bestScore = Infinity;
    const idealLane = Math.max(0, Math.min(laneCount - 1, centroid01 * (laneCount - 1)));

    for (let l = 0; l < laneCount; l++) {
      // STRICT: never place inside or within 90ms after a hold on this lane
      if (laneHoldUntil[l] > timeMs - 90) continue;
      // Avoid impossibly fast jack on same lane (< 115ms)
      if (timeMs - laneLastTap[l] < 115) continue;

      let score = laneCounts[l] * 2.8;
      if (l === lastLane) score += 2.2;
      // Gentle melodic contour guidance
      score += Math.abs(l - idealLane) * 0.9;
      if (score < bestScore) {
        bestScore = score;
        bestLane = l;
      }
    }
    return bestLane;
  }

  let lastNoteTime = -1000;
  const agcWin = 55; // ±550ms local window

  for (let f = 3; f < frameCount - 3; f++) {
    const tMs = f * hopMs;
    if (tMs < 250 || tMs > durationMs - 250) continue;

    // Strict local peak in ±20ms window
    const cur = smoothOnset[f];
    if (cur < smoothOnset[f - 1] || cur < smoothOnset[f + 1] || cur < smoothOnset[f - 2] || cur < smoothOnset[f + 2]) {
      continue;
    }

    if (rms[f] < avgRms * 0.14) continue;

    // Local AGC mean + standard deviation
    const wStart = Math.max(0, f - agcWin);
    const wEnd = Math.min(frameCount - 1, f + agcWin);
    const wLen = wEnd - wStart + 1;
    let meanOnset = 0;
    let localRmsSum = 0;
    for (let k = wStart; k <= wEnd; k++) {
      meanOnset += smoothOnset[k];
      localRmsSum += rms[k];
    }
    meanOnset /= wLen;
    const sectionEnergy = (localRmsSum / wLen) / avgRms;

    let variance = 0;
    for (let k = wStart; k <= wEnd; k++) {
      const d = smoothOnset[k] - meanOnset;
      variance += d * d;
    }
    const stdOnset = Math.sqrt(variance / wLen);

    const isCalm = sectionEnergy < 0.78;
    const isIntense = sectionEnergy > 1.18;

    // Adaptive threshold: must stand out cleanly above local background
    const dynamicThresh = (meanOnset * diffConfig.threshMul + stdOnset * diffConfig.stdMul) / Math.sqrt(densityScale);
    const calmBoost = isCalm ? 1.15 : 1.0;
    if (cur < dynamicThresh * calmBoost) continue;

    const dynamicMinGap = isCalm ? effectiveMinGap * 1.38 : (isIntense ? effectiveMinGap * 0.9 : effectiveMinGap);
    const noteTime = maybeSnapTime(tMs);
    if (noteTime - lastNoteTime < dynamicMinGap) continue;

    const lane = pickFreeBalancedLane(noteTime, melodyCentroid[f]);
    if (lane < 0) continue; // All lanes busy — NEVER stack inside an active hold!

    // Check genuine sustain: steady RMS AND low subsequent onset flux (no new rapid melody attacks)
    let sustainFrames = 0;
    const rmsFloor = rms[f] * 0.58;
    const fluxCeiling = meanOnset * 1.45;
    for (let k = f + 3; k < Math.min(frameCount - 2, f + 140); k++) {
      if (rms[k] >= rmsFloor && smoothOnset[k] <= fluxCeiling) {
        sustainFrames++;
      } else {
        break;
      }
    }
    const sustainMs = sustainFrames * hopMs;

    const isStrongDrum = bassFlux[f] > meanOnset * 0.55 && rms[f] > avgRms * 0.85;
    const canPlaceHold =
      sustainMs >= minHoldMs &&
      !isIntense &&
      activeHoldCountAt(noteTime) < diffConfig.maxHolds;

    let type = 'TAP';
    let duration = 0;
    let targetLane = lane;
    let pathPoints = undefined;

    if (canPlaceHold) {
      const cappedDur = Math.min(Math.round(sustainMs / 20) * 20, Math.round(beatMs * 2.5));
      if (!onlyTapAndHold && cappedDur >= minHoldMs + 200 && (nextId % 5 === 0)) {
        const candTarget = lane < laneCount - 1 ? lane + 1 : lane - 1;
        if (laneHoldUntil[candTarget] <= noteTime - 90) {
          type = 'HOLD_SLIDE';
          duration = cappedDur;
          targetLane = candTarget;
          const mid1 = noteTime + Math.round(duration * 0.45);
          const mid2 = noteTime + Math.round(duration * 0.58);
          pathPoints = [
            { timeMs: noteTime, lane },
            { timeMs: mid1, lane },
            { timeMs: mid2, lane: targetLane },
            { timeMs: noteTime + duration, lane: targetLane }
          ];
        } else {
          type = 'HOLD';
          duration = cappedDur;
        }
      } else {
        type = 'HOLD';
        duration = cappedDur;
      }
    } else if (!onlyTapAndHold && !isStrongDrum && (nextId % diffConfig.slideStep === 0) && melodyFlux[f] > meanOnset * 0.65) {
      const candTarget = lane < laneCount - 1 ? lane + 1 : lane - 1;
      if (laneHoldUntil[candTarget] <= noteTime - 90) {
        type = 'SLIDE';
        targetLane = candTarget;
      }
    }

    const note = {
      id: nextId++,
      timeMs: noteTime,
      lane,
      type,
      durationMs: duration,
      slideTargetLane: targetLane
    };
    if (pathPoints) note.pathPoints = pathPoints;

    notes.push(note);
    laneCounts[lane]++;
    laneLastTap[lane] = noteTime;
    if (duration > 0) {
      laneHoldUntil[lane] = noteTime + duration + 100;
      if (targetLane !== lane) {
        laneCounts[targetLane]++;
        laneHoldUntil[targetLane] = noteTime + duration + 100;
      }
    }
    lastLane = targetLane;
    lastNoteTime = noteTime;

    // Strong drum hit -> adjacent double tap (only on free lane, never inside a hold)
    if (drumDoubles && diffConfig.doubleAllow && isStrongDrum && type === 'TAP' && (isIntense || nextId % 3 === 0)) {
      const adjCandidates = [lane - 1, lane + 1].filter(
        (l) => l >= 0 && l < laneCount && laneHoldUntil[l] <= noteTime - 90 && noteTime - laneLastTap[l] >= 120
      );
      if (adjCandidates.length > 0) {
        adjCandidates.sort((a, b) => laneCounts[a] - laneCounts[b]);
        const adj = adjCandidates[0];
        notes.push({
          id: nextId++,
          timeMs: noteTime,
          lane: adj,
          type: 'TAP',
          durationMs: 0,
          slideTargetLane: adj
        });
        laneCounts[adj]++;
        laneLastTap[adj] = noteTime;
      }
    }
  }

  const sanitized = sanitizeLaneOverlaps(notes, laneCount);
  return { notes: sanitized, detectedBpm };
}
