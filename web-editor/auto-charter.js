/**
 * High-Precision DSP + Beam-Search Auto-Chart Generator for 《星律回响 · ASTRA ECHO》
 *
 * Inspired by pure-DSP rhythm-editor analysis & two-hand ergonomic beam search:
 * 1. Downsamples any AudioBuffer to 22,050 Hz mono -> 1024-pt Hann FFT (hop = 220 samples, ~9.98ms).
 * 2. 3 quantile-normalized spectral flux bands + parabolic sub-frame peak interpolation.
 * 3. Tonal Attack Extractor (`tonalAttacks`) to catch prominent lead melody notes even under heavy compression.
 * 4. Autocorrelation Tempo + Circular Beat-Phase Fitting (`fitPhase`) + Local Phase Drift + Conditional Snap.
 * 5. Harmonic Sustain (`sustains`) & Pitch-Contour Motion (`pitchMotion`) -> authentic TAP / HOLD / SLIDE / HOLD_SLIDE.
 * 6. Section Activity + Chroma Chorus Recurrence -> sparse melodic verses & dense energetic choruses.
 * 7. Two-Hand Ergonomic Beam Search (`assignLanes`) -> balanced left/right hand load & strict zero-overlap guarantee.
 */

const TARGET_SR = 22050;
const FFT_SIZE = 1024;
const HOP = 220;
const FRAME_MS = (1000 * HOP) / TARGET_SR;
const FIRST_TONE = 6;
const TONE_BINS = 144;
const MAX_BIN = 394;
const TAU = 2 * Math.PI;

const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
const wrap = (v, period) => ((v % period) + period) % period;
const signedPhase = (v, period) => wrap(v + period / 2, period) - period / 2;

function quantile(values, q) {
  if (!values || values.length === 0) return 0;
  const sorted = Float32Array.from(values).sort();
  return sorted[Math.floor((sorted.length - 1) * q)];
}

function resampleToMono22050(audioBuffer) {
  const srcRate = audioBuffer.sampleRate;
  const srcLen = audioBuffer.length;
  const channels = audioBuffer.numberOfChannels;

  const monoSrc = new Float32Array(srcLen);
  for (let c = 0; c < channels; c++) {
    const ch = audioBuffer.getChannelData(c);
    for (let i = 0; i < srcLen; i++) {
      monoSrc[i] += ch[i] / channels;
    }
  }

  if (Math.abs(srcRate - TARGET_SR) < 10) return monoSrc;

  const dstLen = Math.max(1, Math.round((srcLen * TARGET_SR) / srcRate));
  const dst = new Float32Array(dstLen);
  const ratio = srcRate / TARGET_SR;
  for (let i = 0; i < dstLen; i++) {
    const pos = i * ratio;
    const idx = Math.floor(pos);
    const frac = pos - idx;
    const s0 = monoSrc[idx] || 0;
    const s1 = monoSrc[Math.min(srcLen - 1, idx + 1)] || 0;
    dst[i] = s0 + (s1 - s0) * frac;
  }
  return dst;
}

function createFftTransform() {
  const real = new Float64Array(FFT_SIZE);
  const imag = new Float64Array(FFT_SIZE);
  const indices = new Uint16Array(FFT_SIZE);
  const win = new Float64Array(FFT_SIZE);
  const cos = new Float64Array(FFT_SIZE / 2);
  const sin = new Float64Array(FFT_SIZE / 2);

  for (let i = 0; i < FFT_SIZE; i++) {
    indices[i] = (indices[i >> 1] >> 1) | ((i & 1) * (FFT_SIZE / 2));
    win[i] = 0.5 - 0.5 * Math.cos((TAU * i) / FFT_SIZE);
  }
  for (let i = 0; i < cos.length; i++) {
    cos[i] = Math.cos((-TAU * i) / FFT_SIZE);
    sin[i] = Math.sin((-TAU * i) / FFT_SIZE);
  }

  return (samples, offset) => {
    for (let i = 0; i < FFT_SIZE; i++) {
      const idx = indices[i];
      real[i] = (samples[offset + idx] || 0) * win[idx];
      imag[i] = 0;
    }
    for (let size = 2; size <= FFT_SIZE; size <<= 1) {
      const half = size >> 1;
      const stride = FFT_SIZE / size;
      for (let start = 0; start < FFT_SIZE; start += size) {
        for (let i = 0, phase = 0; i < half; i++, phase += stride) {
          const l = start + i;
          const r = l + half;
          const re = real[r] * cos[phase] - imag[r] * sin[phase];
          const im = real[r] * sin[phase] + imag[r] * cos[phase];
          real[r] = real[l] - re;
          imag[r] = imag[l] - im;
          real[l] += re;
          imag[l] += im;
        }
      }
    }
    return { real, imag };
  };
}

function computeSpectrum(samples) {
  const frames = Math.ceil(samples.length / HOP);
  const bands = Array.from({ length: 3 }, () => new Float32Array(frames));
  const loudness = new Float32Array(frames);
  const bassLevel = new Float32Array(frames);
  const tones = new Float32Array(frames * TONE_BINS);
  const prev = new Float32Array(MAX_BIN + 1);
  const transform = createFftTransform();

  for (let f = 0; f < frames; f++) {
    const { real, imag } = transform(samples, f * HOP - FFT_SIZE / 2);
    let power = 0;
    let bassPower = 0;
    for (let bin = 2; bin <= MAX_BIN; bin++) {
      const sq = real[bin] * real[bin] + imag[bin] * imag[bin];
      const mag = Math.sqrt(sq);
      power += sq;
      if (bin <= 7) bassPower += sq;
      if (bin >= FIRST_TONE && bin < FIRST_TONE + TONE_BINS) {
        tones[f * TONE_BINS + bin - FIRST_TONE] = mag;
      }
      const compressed = Math.log1p(mag * 0.28);
      const band = bin <= 7 ? 0 : bin <= 83 ? 1 : 2;
      bands[band][f] += Math.max(0, compressed - prev[bin]);
      prev[bin] = compressed;
    }
    loudness[f] = Math.sqrt(power);
    bassLevel[f] = Math.sqrt(bassPower);
  }
  return { bands, loudness, bassLevel, tones };
}

function extractTonalAttacks({ tones, loudness }) {
  const curve = new Float32Array(loudness.length);
  const ref = Math.max(1e-4, quantile(loudness, 0.8));
  for (let f = 7; f < curve.length - 10; f++) {
    if (loudness[f + 4] < ref * 0.038) continue;
    const lead = (f + 4) * TONE_BINS;
    const tail = (f + 9) * TONE_BINS;
    const before = (f - 5) * TONE_BINS;
    let strongest = 0;
    for (let bin = 5; bin < 91; bin++) {
      const atk = tones[lead + bin];
      if (atk <= 1 || atk < tones[lead + bin - 1] || atk <= tones[lead + bin + 1]) continue;
      const floor = Math.max(tones[before + bin], tones[(f - 2) * TONE_BINS + bin]);
      const stable = Math.max(tones[tail + bin], 0.68 * tones[tail + bin - 1], 0.68 * tones[tail + bin + 1]);
      const rise = Math.min(atk, stable * 1.55) - floor * 1.25;
      if (rise > strongest) strongest = rise;
    }
    curve[f] = strongest / Math.max(ref * 0.11, loudness[f + 4] * 0.55);
  }
  const scale = Math.max(0.12, quantile(curve, 0.91));
  for (let i = 0; i < curve.length; i++) curve[i] = Math.min(2, curve[i] / scale);
  return curve;
}

function extractOnsetEvents({ bands, loudness }) {
  const scales = bands.map((b) => Math.max(0.0001, quantile(b, 0.9)));
  const level = quantile(loudness, 0.9);
  if (!(level > 0.001)) return { flux: new Float32Array(loudness.length), events: [] };

  const flux = new Float32Array(loudness.length);
  const weights = [0.39, 0.46, 0.15];
  const sums = new Float64Array(loudness.length + 1);

  for (let i = 0; i < flux.length; i++) {
    if (loudness[i] > level * 0.025) {
      for (let b = 0; b < 3; b++) {
        flux[i] += weights[b] * Math.min(3, bands[b][i] / scales[b]);
      }
    }
    sums[i + 1] = sums[i] + flux[i];
  }

  const events = [];
  for (let i = 1; i < flux.length - 1; i++) {
    const val = flux[i];
    if (val < flux[i - 1] || val <= flux[i + 1]) continue;
    const left = Math.max(0, i - 14);
    const right = Math.min(flux.length, i + 15);
    const local = (sums[right] - sums[left]) / (right - left);
    if (val < Math.max(0.19, local * 1.2)) continue;

    const curve = flux[i - 1] + flux[i + 1] - 2 * val;
    const corr = curve ? clamp((0.5 * (flux[i - 1] - flux[i + 1])) / curve, -0.5, 0.5) : 0;
    const color = bands.map((b, j) => Math.min(3, b[i] / scales[j]));
    const ev = {
      frame: i,
      time: (i + corr) * FRAME_MS,
      strength: 0.64 * val + 0.36 * (val - local),
      bass: color[0],
      drum: 0.6 * color[0] + 0.4 * color[2],
      balance: (color[2] - color[0]) / Math.max(0.1, color[0] + color[1] + color[2])
    };
    const recent = events[events.length - 1];
    if (recent && ev.time - recent.time < 45) {
      if (ev.strength > recent.strength) events[events.length - 1] = ev;
    } else {
      events.push(ev);
    }
  }
  return { flux, events };
}

function mergeMelodyEvents(events, tonal, { loudness, bands }) {
  const extra = [];
  const peaks = [];
  const ref = quantile(loudness, 0.8);

  for (let i = 4; i < tonal.length - 5; i++) {
    const val = tonal[i];
    if (val < 0.68 || val < tonal[i - 1] || val <= tonal[i + 1]) continue;
    if (val < Math.max(tonal[i - 4], tonal[i + 4]) * 0.98) continue;
    const recent = peaks[peaks.length - 1];
    if (recent && i - recent.frame < 8) {
      if (val > recent.value) peaks[peaks.length - 1] = { frame: i, value: val };
    } else {
      peaks.push({ frame: i, value: val });
    }
  }

  let onset = 0;
  for (const peak of peaks) {
    const time = peak.frame * FRAME_MS;
    while (events[onset + 1] && events[onset + 1].time <= time) onset++;
    const before = events[onset];
    const after = events[onset + 1];
    if (!before) continue;
    const nearest = after && Math.abs(after.time - time) < Math.abs(before.time - time) ? after : before;
    if (Math.abs(nearest.time - time) <= 46) {
      nearest.lead = Math.max(nearest.lead || 0, peak.value);
    } else if (peak.value >= 1.08 && loudness[peak.frame + 4] >= ref * 0.07) {
      extra.push({
        frame: peak.frame,
        time,
        lead: peak.value,
        strength: 0.22 + peak.value * 0.29,
        bass: 0,
        drum: 0,
        balance: bands[2][peak.frame] > bands[0][peak.frame] ? 0.1 : 0
      });
    }
  }
  return events.concat(extra).sort((a, b) => a.time - b.time);
}

function fitPhase(events, period) {
  let sinSum = 0;
  let cosSum = 0;
  let wSum = 0;
  for (const ev of events) {
    const w = ev.strength * ev.strength * (0.65 + 0.35 * Math.min(2, ev.bass));
    const ang = (TAU * ev.time) / period;
    sinSum += w * Math.sin(ang);
    cosSum += w * Math.cos(ang);
    wSum += w;
  }
  return {
    phase: wrap((Math.atan2(sinSum, cosSum) * period) / TAU, period),
    confidence: wSum ? Math.hypot(sinSum, cosSum) / wSum : 0
  };
}

function estimateTempo(flux, events) {
  const shortest = Math.round(60000 / (190 * FRAME_MS));
  const longest = Math.round(60000 / (64 * FRAME_MS));
  const corr = new Float64Array(2 * longest + 1);
  let mean = 0;
  for (let i = 0; i < flux.length; i++) mean += flux[i];
  mean /= Math.max(1, flux.length);

  const sig = new Float32Array(flux.length);
  for (let i = 0; i < flux.length; i++) sig[i] = flux[i] - mean;

  for (let lag = Math.floor(shortest / 2); lag < corr.length; lag++) {
    let dot = 0, a = 0, b = 0;
    for (let i = lag; i < sig.length; i++) {
      const x = sig[i];
      const y = sig[i - lag];
      dot += x * y;
      a += x * x;
      b += y * y;
    }
    corr[lag] = a && b ? dot / Math.sqrt(a * b) : 0;
  }

  const scores = [];
  for (let lag = shortest; lag <= longest; lag++) {
    const bpm = 60000 / (lag * FRAME_MS);
    const prior = 0.07 * Math.exp(-0.5 * ((bpm - 124) / 32) ** 2);
    const halfIdx = Math.round(lag / 2);
    scores.push({
      lag,
      score: corr[lag] + 0.34 * (corr[lag * 2] || 0) + 0.12 * (corr[halfIdx] || 0) + prior
    });
  }

  const peaks = scores
    .filter((item, i) => item.score >= (scores[i - 1]?.score ?? -Infinity) && item.score >= (scores[i + 1]?.score ?? -Infinity))
    .sort((a, b) => b.score - a.score)
    .slice(0, 5);

  if (peaks.length === 0) {
    return { period: 60000 / 128, phase: 0, confidence: 0 };
  }

  let best = { score: -Infinity, period: 60000 / 128, phase: 0, confidence: 0, peak: peaks[0], change: 0 };
  const evaluate = (peak, change) => {
    const period = (peak.lag + change) * FRAME_MS;
    if (period <= 100) return;
    const beat = fitPhase(events, period);
    const eighth = fitPhase(events, period / 2);
    const sixteenth = fitPhase(events, period / 4);
    const score = peak.score + 0.49 * beat.confidence + 0.23 * eighth.confidence + 0.21 * sixteenth.confidence - 0.015 * Math.abs(change);
    if (score <= best.score) return;
    let phase = beat.phase;
    if (eighth.confidence >= 0.22) phase = eighth.phase + Math.round((phase - eighth.phase) / (period / 2)) * (period / 2);
    if (sixteenth.confidence >= 0.24) phase = sixteenth.phase + Math.round((phase - sixteenth.phase) / (period / 4)) * (period / 4);
    best = {
      period,
      phase: wrap(phase, period),
      confidence: Math.max(beat.confidence, eighth.confidence, sixteenth.confidence),
      score,
      peak,
      change
    };
  };

  for (const peak of peaks) {
    for (let change = -1.2; change <= 1.21; change += 0.06) evaluate(peak, change);
  }
  const { peak, change } = best;
  for (let fine = -0.06; fine <= 0.061; fine += 0.004) evaluate(peak, change + fine);
  return best;
}

function buildLocalPhases(events, tempo, duration) {
  const stride = 4000;
  const phases = [];
  let first = 0;
  let last = 0;
  for (let center = 0; center <= duration + stride; center += stride) {
    while (first < events.length && events[first].time < center - stride) first++;
    while (last < events.length && events[last].time <= center + stride) last++;
    const nearby = events.slice(first, last);
    const sub = tempo.period / 4;
    const local = fitPhase(nearby, sub);
    phases.push(
      nearby.length >= 6 && local.confidence >= 0.27
        ? clamp(signedPhase(local.phase - tempo.phase, sub), -0.08 * tempo.period, 0.08 * tempo.period)
        : 0
    );
  }
  return (time) => {
    const idx = Math.max(0, Math.min(phases.length - 2, Math.floor(time / stride)));
    const frac = clamp(time / stride - idx, 0, 1);
    const ph = phases[idx] + frac * ((phases[idx + 1] ?? phases[idx]) - phases[idx]);
    return tempo.phase + ph;
  };
}

function detectSustains(event, tones, period, duration) {
  const frames = tones.length / TONE_BINS;
  const read = (f, bin) => tones[clamp(f, 0, frames - 1) * TONE_BINS + bin];
  const avg = (from, to, bin) => {
    let tot = 0;
    for (let f = from; f <= to; f++) tot += read(f, bin);
    return tot / (to - from + 1);
  };
  const i = event.frame;
  const anchors = [];
  for (let bin = 1; bin < TONE_BINS - 1; bin++) {
    const atk = avg(i + 2, i + 7, bin);
    const left = avg(i + 2, i + 7, bin - 1);
    const right = avg(i + 2, i + 7, bin + 1);
    if (atk < 1.1 || atk < left || atk <= right) continue;
    const bg = avg(i - 9, i - 4, bin);
    if (atk < bg * 1.8 + 0.6) continue;
    const score = (atk - bg) * (bin < 4 ? 0.65 : 1);
    const denom = left + right - 2 * atk;
    const shift = denom ? clamp((0.5 * (left - right)) / denom, -0.5, 0.5) : 0;
    anchors.push({ bin, pitch: FIRST_TONE + bin + shift, attack: atk, background: bg, score });
  }
  anchors.sort((a, b) => b.score - a.score);
  const lastFrame = Math.min(frames - 1, i + Math.round((period * 3.5) / FRAME_MS));
  const found = [];
  let primary = null;

  for (const anchor of anchors.slice(0, 8)) {
    if (primary) {
      const low = Math.min(primary.pitch, anchor.pitch);
      const high = Math.max(primary.pitch, anchor.pitch);
      const harmonic = Math.round(high / low) * low;
      if (anchor.score < primary.score * 0.55 || high - low < 2.7 || Math.abs(high - harmonic) < Math.max(1.15, high * 0.023)) {
        continue;
      }
    }
    const thresh = Math.max(anchor.attack * 0.38, anchor.background * 1.24, 0.8);
    let below = 0;
    for (let f = i + 8; f <= lastFrame; f++) {
      const amp = Math.max(read(f, anchor.bin), 0.75 * read(f, anchor.bin - 1), 0.75 * read(f, anchor.bin + 1));
      below = amp < thresh ? below + 1 : 0;
      if (below < 6) continue;
      const end = Math.round((f - below + 1) * FRAME_MS);
      const length = end - event.time;
      if (length >= 145 && end < duration) {
        found.push({
          end,
          confidence: Math.min(2, anchor.attack / (anchor.background + 1)) + Math.min(2, length / period) * 0.28
        });
        primary ??= anchor;
      }
      break;
    }
    if (found.length === 2) break;
  }
  return found;
}

function detectPitchMotion(event, tones, loudness, period, duration) {
  const i = event.frame;
  const read = (f, bin) => tones[f * TONE_BINS + bin] || 0;
  const initial = i + 2;
  if (initial >= loudness.length - 6) return null;

  let anchor = 0;
  let strength = 0;
  for (let bin = 4; bin < 92; bin++) {
    const lvl = read(initial, bin);
    if (lvl < read(initial, bin - 1) || lvl <= read(initial, bin + 1)) continue;
    const rise = lvl - 1.35 * read(Math.max(0, i - 5), bin);
    const w = bin >= 8 ? 1 : 0.8;
    if (rise * w > strength) {
      anchor = bin;
      strength = rise * w;
    }
  }
  const atk = read(initial, anchor);
  if (!anchor || strength < 1.6 || atk < loudness[initial] * 0.12) return null;

  const contour = [];
  const stop = Math.min(loudness.length - 1, i + Math.round((3.5 * period) / FRAME_MS));
  let pos = anchor;
  let misses = 0;

  for (let f = initial; f <= stop; f++) {
    let best = null;
    const dist = Math.max(2, Math.round((FIRST_TONE + pos) * 0.065));
    for (let bin = Math.max(2, Math.floor(pos - dist)); bin <= Math.min(TONE_BINS - 2, Math.ceil(pos + dist)); bin++) {
      const lvl = read(f, bin);
      const left = read(f, bin - 1);
      const right = read(f, bin + 1);
      if (lvl < Math.max(atk * 0.29, loudness[f] * 0.085, 0.85) || lvl < left || lvl <= right) continue;
      const denom = left + right - 2 * lvl;
      const shift = denom ? clamp((0.5 * (left - right)) / denom, -0.5, 0.5) : 0;
      const pitch = bin + shift;
      const change = Math.abs(12 * Math.log2((FIRST_TONE + pitch) / (FIRST_TONE + pos)));
      if (change > 1.6) continue;
      const score = lvl / (1 + change * 0.72);
      if (!best || score > best.score) best = { pitch, score };
    }
    if (!best) {
      if (++misses >= 4) break;
      continue;
    }
    misses = 0;
    pos = best.pitch;
    contour.push({ time: f * FRAME_MS, pitch: 12 * Math.log2(FIRST_TONE + pos) });
  }
  if (contour.length < 7) return null;

  const smooth = contour.map(({ time, pitch }, idx) => ({
    time,
    pitch: ((contour[idx - 1]?.pitch ?? pitch) + 2 * pitch + (contour[idx + 1]?.pitch ?? pitch)) / 4
  }));
  const tail = smooth[smooth.length - 1];
  const first = smooth[0];
  const end = Math.round(tail.time + FRAME_MS);
  const length = end - event.time;
  const variation = (from, to) => {
    let tot = 0;
    for (let idx = from + 1; idx <= to; idx++) tot += Math.abs(smooth[idx].pitch - smooth[idx - 1].pitch);
    return tot;
  };
  const delta = tail.pitch - first.pitch;
  const ratio = Math.abs(delta) / Math.max(0.01, variation(0, smooth.length - 1));
  const dir = Math.sign(delta);
  const coverage = (smooth.length * FRAME_MS) / (tail.time - first.time + FRAME_MS);
  if (coverage < 0.85 || end >= duration) return null;

  if (length >= 75 && length <= 205 && Math.abs(delta) >= 2.1 && ratio >= 0.74 && misses >= 4) {
    return { type: 'slide', direction: dir, end, endPitch: tail.pitch, confidence: ratio };
  }
  if (length < 250 || misses < 4) return null;

  if (Math.abs(delta) >= 2.65 && ratio >= 0.64) {
    const startMove = smooth.findIndex((p) => dir * (p.pitch - first.pitch) >= Math.abs(delta) * 0.18);
    const endMove = smooth.findIndex((p) => dir * (p.pitch - first.pitch) >= Math.abs(delta) * 0.82);
    if (startMove < 0 || endMove < 0 || smooth[endMove].time - smooth[startMove].time < 88) return null;
    const ride = Math.round(smooth[Math.max(0, startMove - 2)].time);
    const settle = Math.round(smooth[Math.min(smooth.length - 1, endMove + 2)].time);
    const pauseBefore = ride - event.time;
    const pauseAfter = end - settle;
    const waypoint = pauseBefore >= pauseAfter && pauseBefore >= 150 && end - ride >= 155
      ? { ride }
      : (pauseAfter >= 150 && settle - event.time >= 155 ? { settle } : {});
    return { type: 'trace', direction: dir, end, endPitch: tail.pitch, confidence: ratio, ...waypoint };
  }

  let bend = -1;
  let height = 0;
  for (let j = 1; j < smooth.length - 1; j++) {
    const p = smooth[j];
    if (p.time - event.time < 155 || end - p.time < 155) continue;
    const before = p.pitch - first.pitch;
    const after = tail.pitch - p.pitch;
    if (Math.sign(before) === Math.sign(after)) continue;
    const exc = Math.min(Math.abs(before), Math.abs(after));
    if (exc > height) {
      bend = j;
      height = exc;
    }
  }
  if (bend < 0 || height < 2.35) return null;
  const turn = smooth[bend];
  const stability = Math.min(
    Math.abs(turn.pitch - first.pitch) / Math.max(0.01, variation(0, bend)),
    Math.abs(tail.pitch - turn.pitch) / Math.max(0.01, variation(bend, smooth.length - 1))
  );
  return stability >= 0.68
    ? { type: 'trace', direction: Math.sign(turn.pitch - first.pitch), end, endPitch: tail.pitch, turn: Math.round(turn.time), confidence: stability }
    : null;
}

function trackMotions(events, features, period, duration) {
  const { tones, loudness } = features;
  let active = [];
  return events.filter((ev) => {
    const motion = detectPitchMotion(ev, tones, loudness, period, duration);
    if (!motion) return true;
    active = active.filter((other) => other.end > ev.time);
    const sameTail = active.some((other) => Math.abs(other.end - motion.end) <= 40 && Math.abs(other.endPitch - motion.endPitch) < 1.2);
    if (sameTail) {
      const before = loudness.subarray(Math.max(0, ev.frame - 6), ev.frame);
      const after = loudness.subarray(ev.frame, ev.frame + 6);
      if (Math.max(...after) < Math.max(...before) * 1.52) return false;
    }
    ev.motion = motion;
    active.push(motion);
    return true;
  });
}

function describeSections(features, events, period, duration) {
  const { loudness, bassLevel, tones } = features;
  const width = period * 4;
  const ref = Math.max(0.001, quantile(loudness, 0.82));
  const bassRef = Math.max(0.001, quantile(bassLevel, 0.82));
  const sections = [];
  const pitchClasses = Array.from({ length: 90 }, (_, bin) => {
    const freq = ((FIRST_TONE + bin) * TARGET_SR) / FFT_SIZE;
    return wrap(Math.round(69 + 12 * Math.log2(freq / 440)), 12);
  });

  let next = 0;
  for (let start = 0; start < duration; start += width) {
    const end = Math.min(duration, start + width);
    const from = Math.min(loudness.length - 1, Math.floor(start / FRAME_MS));
    const to = Math.min(loudness.length, Math.max(from + 1, Math.floor(end / FRAME_MS)));
    const energy = quantile(loudness.subarray(from, to), 0.64) / ref;
    const bass = quantile(bassLevel.subarray(from, to), 0.62) / bassRef;

    let hits = 0;
    while (next < events.length && events[next].time < end) {
      if (events[next].drum > 0.81 && events[next].strength > 0.47) hits++;
      next++;
    }
    const drive = clamp(hits / 6, 0, 1);
    const level = clamp((energy - 0.23) / 0.64, 0, 1);
    const lowEnd = clamp((bass - 0.2) / 0.78, 0, 1);

    const texture = new Float64Array(24);
    const middle = (from + to) / 2;
    for (let f = from; f < to; f += 5) {
      const pos = f * TONE_BINS;
      const half = f < middle ? 0 : 12;
      const floor = loudness[f] * 0.085;
      for (let bin = 1; bin < pitchClasses.length; bin++) {
        const val = tones[pos + bin];
        if (val > floor && val > tones[pos + bin - 1] && val >= tones[pos + bin + 1]) {
          texture[half + pitchClasses[bin]] += Math.sqrt(val);
        }
      }
    }
    const norm = Math.hypot(...texture) || 1;
    const chroma = Array.from(texture, (v) => v / norm);
    sections.push({ start, end, energy, bass, drive, chroma, activity: 0.63 * level + 0.2 * lowEnd + 0.17 * drive });
  }

  const recurrence = new Float32Array(sections.length);
  const similar = (a, b) =>
    a.chroma.reduce((sum, v, j) => sum + v * b.chroma[j], 0) - 0.2 * Math.abs(a.energy - b.energy) - 0.11 * Math.abs(a.drive - b.drive);

  for (let i = 0; i + 1 < sections.length; i++) {
    for (let j = i + 6; j + 1 < sections.length && j - i <= 112; j++) {
      if (Math.min(sections[i].activity, sections[j].activity) < 0.52) continue;
      const match = Math.min(similar(sections[i], sections[j]), similar(sections[i + 1], sections[j + 1]));
      const str = clamp((match - 0.86) / 0.13, 0, 1);
      for (const idx of [i, i + 1, j, j + 1]) recurrence[idx] = Math.max(recurrence[idx], str);
    }
  }

  return sections.map(({ chroma, ...sec }, i) => {
    const neighbors = [sections[i - 1], sec, sections[i + 1]].filter(Boolean);
    const mean = neighbors.reduce((tot, item) => tot + item.activity, 0) / neighbors.length;
    const lvl = 0.6 * sec.activity + 0.4 * mean;
    return { ...sec, activity: clamp(lvl + 0.13 * lvl * recurrence[i], 0, 1) };
  });
}

function analyzeAudioBuffer(audioBuffer, options = {}) {
  const samples = resampleToMono22050(audioBuffer);
  const duration = Math.round((samples.length * 1000) / TARGET_SR);
  const features = computeSpectrum(samples);
  const { flux, events: onsets } = extractOnsetEvents(features);
  if (onsets.length < 2) {
    return { candidates: [], duration, bpm: options.bpm || 128, offset: options.offsetMs || 0, sections: [] };
  }

  const tempo = options.autoDetectBpm !== false
    ? estimateTempo(flux, onsets)
    : { period: 60000 / Math.max(40, options.bpm || 128), phase: options.offsetMs || 0, confidence: 1 };

  const phaseAt = buildLocalPhases(onsets, tempo, duration);
  const strong = onsets.filter((ev) => ev.strength >= 0.42);
  const gridEvents = strong.length >= 8 ? strong : onsets;
  const gridFit = gridEvents.filter((ev) => Math.abs(signedPhase(ev.time - phaseAt(ev.time), tempo.period / 4)) <= 23).length / gridEvents.length;
  const canSnap = (options.quantize || (tempo.confidence >= 0.19 && gridFit >= 0.54));

  const tonal = extractTonalAttacks(features);
  const events = trackMotions(mergeMelodyEvents(onsets, tonal, features), features, tempo.period, duration);
  const sections = describeSections(features, events, tempo.period, duration);

  const unique = new Map();
  for (const ev of events) {
    const ph = phaseAt(ev.time);
    const residual = signedPhase(ev.time - ph, tempo.period / 4);
    const time = Math.round(ev.time - (canSnap && Math.abs(residual) <= 23 ? residual : 0));
    const prev = unique.get(time);
    if (time < 0 || time >= duration) continue;
    if (prev && prev.strength + (prev.lead || 0) * 0.3 >= ev.strength + (ev.lead || 0) * 0.3) continue;
    unique.set(time, {
      ...ev,
      rawTime: Math.round(ev.time),
      time,
      beat: Math.exp(-0.5 * (signedPhase(ev.time - ph, tempo.period) / 55) ** 2),
      halfBeat: Math.exp(-0.5 * (signedPhase(ev.time - ph, tempo.period / 2) / 32) ** 2),
      holds: detectSustains(ev, features.tones, tempo.period, duration)
    });
  }

  return {
    candidates: Array.from(unique.values()).sort((a, b) => a.time - b.time),
    duration,
    bpm: Math.round((60000 / tempo.period) * 100) / 100,
    offset: Math.round(tempo.phase),
    sections
  };
}

// ============================================================================
// Chart Event Selection, Chord Picking, Beam-Search Lane Scheduler & Motion
// ============================================================================
const PROFILES = {
  EASY:   { gap: 0.62, density: [2, 5],  beatWeight: 0.24, floor: 0.35, handGap: 225, laneGap: 310 },
  NORMAL: { gap: 0.36, density: [2, 8],  beatWeight: 0.19, floor: 0.29, handGap: 157, laneGap: 205 },
  HARD:   { gap: 0.21, density: [3, 12], beatWeight: 0.14, floor: 0.23, handGap: 114, laneGap: 150 },
  MASTER: { gap: 0.14, density: [4, 16], beatWeight: 0.10, floor: 0.18, handGap: 88,  laneGap: 115 }
};

function selectEvents(analysis, difficulty, densityScale = 1.0) {
  const beat = 60000 / analysis.bpm;
  const width = analysis.sections[1]?.start || beat * 4;
  const scoring = analysis.candidates.map((ev) => {
    const idx = Math.min(analysis.sections.length - 1, Math.floor(ev.time / width));
    return { ...ev, section: idx, activity: analysis.sections[idx]?.activity ?? 0.5 };
  });

  const selected = new Map();
  for (const [key, profile] of Object.entries(PROFILES)) {
    const spacing = Math.max(55, (beat * profile.gap) / densityScale);
    const occupancy = new Map();
    const density = new Map();
    const leads = new Map();
    const slot = (t) => Math.floor(t / 60);
    const add = (ev) => {
      const b = slot(ev.time);
      occupancy.set(b, [...(occupancy.get(b) || []), ev.time]);
      density.set(ev.section, (density.get(ev.section) || 0) + 1);
      selected.set(ev.time, ev);
    };
    for (const ev of selected.values()) add(ev);

    const choices = scoring
      .map((ev) => ({
        event: ev,
        priority:
          0.7 * ev.strength +
          0.43 * (ev.lead || 0) +
          profile.beatWeight * (0.72 * ev.beat + 0.28 * ev.halfBeat) +
          0.075 * (ev.drum || 0) +
          (ev.motion?.type === 'slide' || (ev.motion?.type === 'trace' && key !== 'EASY') ? 0.33 * ev.motion.confidence : 0)
      }))
      .sort((a, b) => b.priority - a.priority || a.event.time - b.event.time);

    const place = (ev, gap) => {
      const [quiet, full] = profile.density;
      const limit = Math.round((quiet + (full - quiet) * ev.activity ** 1.45) * densityScale);
      if (selected.has(ev.time) || (density.get(ev.section) || 0) >= limit) return false;
      const b = slot(ev.time);
      const dist = Math.ceil(gap / 60);
      for (let i = b - dist; i <= b + dist; i++) {
        if (occupancy.get(i)?.some((t) => Math.abs(ev.time - t) < gap)) return false;
      }
      add(ev);
      return true;
    };

    if (key === 'EASY') {
      const melody = choices
        .filter(({ event }) => event.lead >= 1.03)
        .sort((a, b) => b.event.lead + b.priority * 0.27 - a.event.lead - a.priority * 0.27);
      for (const { event } of melody) {
        const used = leads.get(event.section) || 0;
        if (used < 2 && place(event, Math.max(135, beat * 0.38))) leads.set(event.section, used + 1);
      }
    }

    for (const { event } of choices) {
      const floor = profile.floor + 0.23 * (1 - event.activity);
      if (event.strength + 0.17 * (event.lead || 0) + 0.13 * (event.motion?.confidence || 0) >= floor / Math.sqrt(densityScale)) {
        place(event, spacing);
      }
    }

    if (key === difficulty) break;
  }

  return Array.from(selected.values()).sort((a, b) => a.time - b.time);
}

function pickChords(events, difficulty, beat) {
  if (difficulty === 'EASY') return new Set();
  const options = [];
  const spacing = PROFILES[difficulty].handGap + 10;
  for (const [idx, ev] of events.entries()) {
    const { time, activity, drum = 0 } = ev;
    if (activity < (difficulty === 'HARD' || difficulty === 'MASTER' ? 0.58 : 0.69) || drum < 1.02 || ev.strength < 0.58 || Math.max(ev.beat, ev.halfBeat) < 0.51) {
      continue;
    }
    if (time - (events[idx - 1]?.time ?? -Infinity) < spacing || (events[idx + 1]?.time ?? Infinity) - time < spacing) {
      continue;
    }
    options.push({ time, section: ev.section, score: drum + 0.31 * ev.beat + 0.22 * ev.strength + 0.24 * activity });
  }
  options.sort((a, b) => b.score - a.score || a.time - b.time);
  const picked = new Set();
  const sections = new Map();
  const maxPerSec = difficulty === 'MASTER' ? 3 : (difficulty === 'HARD' ? 2 : 1);
  for (const opt of options) {
    const used = sections.get(opt.section) || 0;
    if (used >= maxPerSec || Array.from(picked).some((t) => Math.abs(t - opt.time) < beat * 1.4)) continue;
    picked.add(opt.time);
    sections.set(opt.section, used + 1);
  }
  return picked;
}

const readyAt = (time, end, gap, release) => Math.max(time + gap, end === undefined ? -Infinity : end + release);

function canPlayUpcoming([left, right], nextTime, laterTime, handGap) {
  const firstReady = Math.min(left, right);
  const secondReady = Math.max(left, right);
  return firstReady <= nextTime && Math.min(secondReady, nextTime + handGap) <= laterTime;
}

function assignLanes(events, chords, lanes, difficulty, beat, offset, minimumHold) {
  const profile = PROFILES[difficulty];
  const center = (lanes - 1) / 2;
  const handsForLane = Array.from({ length: lanes }, (_, l) => (l === center ? [0, 1] : [l < center ? 0 : 1]));
  const reaches = lanes === 4 ? [0.5, 1.5, 0.5, 1.5] : lanes === 5 ? [1, 2, 0, 1] : [0.5, 1.5, 2.5, 1.5];

  let beam = [{
    score: 0,
    route: null,
    laneReady: Array(lanes).fill(-Infinity),
    handReady: [-Infinity, -Infinity],
    last: -1,
    lastHand: -1,
    lastTime: -Infinity,
    streak: 0
  }];

  const pairs = handsForLane.flatMap((hands, lane) =>
    hands.includes(0) && handsForLane[lane + 1]?.includes(1) ? [[lane, lane + 1]] : []
  );

  for (const [idx, ev] of events.entries()) {
    const { time } = ev;
    const nextTime = events[idx + 1]?.time ?? Infinity;
    const laterTime = events[idx + 2]?.time ?? Infinity;
    const sustain = ev.holds?.length
      ? ev.holds
      : (ev.motion?.type === 'trace' ? [{ end: ev.motion.end, confidence: 2 * ev.motion.confidence }] : []);
    const holds = sustain
      .filter((h) => h.end - time >= minimumHold)
      .map(({ end, confidence }) => ({ end, reward: 0.23 + 0.145 * confidence }));
    const singles = [{ end: undefined, reward: 0 }, ...holds];
    const simultaneous = chords.has(time) ? [{ end: undefined, otherEnd: undefined, reward: 0.6 }] : [];
    const motion = ev.motion;
    const moving = motion && (
      motion.type === 'slide'
        ? difficulty !== 'EASY' || motion.confidence >= 0.83
        : difficulty !== 'EASY' && (!motion.turn || difficulty === 'HARD' || difficulty === 'MASTER') && motion.end - time >= minimumHold
    );

    if (holds.length === 2) {
      const [first, second] = holds;
      const reward = first.reward + second.reward - 0.13;
      simultaneous.push({ end: first.end, otherEnd: second.end, reward });
      if (first.end !== second.end) simultaneous.push({ end: second.end, otherEnd: first.end, reward });
    }

    const next = [];
    const beatSlot = Math.round(((time - offset) * 2) / beat);
    const desired = reaches[(beatSlot + (ev.balance > 0.18 ? 1 : 0) + 8000) % reaches.length];
    const preferredHand = (beatSlot + Math.floor(beatSlot / 2) + 8000) % 2;

    for (const st of beam) {
      if (simultaneous.length && st.handReady.every((r) => time >= r)) {
        for (const [lane, other] of pairs) {
          if (time < st.laneReady[lane] || time < st.laneReady[other]) continue;
          for (const { end, otherEnd, reward } of simultaneous) {
            const handReady = [readyAt(time, end, profile.handGap, 55), readyAt(time, otherEnd, profile.handGap, 55)];
            if (end !== undefined && !canPlayUpcoming(handReady, nextTime, laterTime, profile.handGap)) continue;
            const laneReady = st.laneReady.slice();
            laneReady[lane] = readyAt(time, end, profile.laneGap, 55);
            laneReady[other] = readyAt(time, otherEnd, profile.laneGap, 55);
            next.push({
              score: st.score + reward,
              route: { previous: st.route, time, lane, end, hand: 0, other, otherEnd },
              laneReady,
              handReady,
              last: -1,
              lastHand: -1,
              lastTime: time,
              streak: 0
            });
          }
        }
      }

      for (let lane = 0; lane < lanes; lane++) {
        if (time < st.laneReady[lane]) continue;
        for (const hand of handsForLane[lane]) {
          if (time < st.handReady[hand]) continue;
          const sameHand = st.lastHand === hand;
          const streak = sameHand ? st.streak + 1 : 1;
          const quick = time - st.lastTime < beat * 0.85;
          const near = Math.abs(lane - center);
          const cost =
            (sameHand && quick ? 0.13 + 0.055 * Math.min(4, streak) : 0) +
            (lane === st.last ? 0.19 : 0) +
            Math.abs(near - desired) * 0.22 +
            (sameHand && quick && st.last >= 0 ? Math.abs(lane - st.last) * 0.045 : 0) +
            (hand === preferredHand ? 0 : 0.065) +
            (moving && !handsForLane[lane + motion.direction]?.includes(hand) ? 0.27 : 0);

          for (const { end, reward } of singles) {
            const laneReady = st.laneReady.slice();
            const handReady = st.handReady.slice();
            laneReady[lane] = readyAt(time, end, profile.laneGap, 55);
            handReady[hand] = readyAt(time, end, profile.handGap, 55);
            if (end !== undefined && !canPlayUpcoming(handReady, nextTime, laterTime, profile.handGap)) continue;
            next.push({
              score: st.score - cost + reward,
              route: { previous: st.route, time, lane, end, hand },
              laneReady,
              handReady,
              last: lane,
              lastHand: hand,
              lastTime: time,
              streak
            });
          }
        }
      }

      next.push({
        ...st,
        score: st.score - (2.5 + ev.strength + (ev.lead || 0) * (difficulty === 'EASY' ? 0.8 : 0.35))
      });
    }

    next.sort((a, b) => b.score - a.score);
    const sigs = new Set();
    beam = [];
    for (const st of next) {
      const sig = [st.last, st.lastHand, st.streak, ...st.handReady, ...st.laneReady].join(',');
      if (sigs.has(sig)) continue;
      sigs.add(sig);
      beam.push(st);
      if (beam.length === 18) break;
    }
  }

  const assigned = [];
  for (let item = beam[0]?.route; item; item = item.previous) {
    const { time, lane, end, hand, other, otherEnd } = item;
    if (other !== undefined) {
      assigned.push({
        timeMs: time,
        lane: other,
        endMs: otherEnd,
        type: otherEnd !== undefined ? 'HOLD' : 'TAP',
        hand: 1
      });
    }
    assigned.push({
      timeMs: time,
      lane,
      endMs: end,
      type: end !== undefined ? 'HOLD' : 'TAP',
      hand
    });
  }
  return assigned.reverse();
}

function addMotions(assigned, events, lanes, difficulty, beat, minimumHold, onlyTapAndHold) {
  if (onlyTapAndHold) {
    return assigned.map((entry, idx) => ({
      id: idx + 1,
      timeMs: entry.timeMs,
      lane: entry.lane,
      type: entry.type,
      durationMs: entry.endMs !== undefined ? Math.max(120, entry.endMs - entry.timeMs) : 0,
      slideTargetLane: entry.lane
    }));
  }

  const profile = PROFILES[difficulty];
  const motions = new Map(events.filter((e) => e.motion).map((e) => [e.time, e.motion]));
  const center = (lanes - 1) / 2;
  const spacing = beat * (difficulty === 'EASY' ? 4 : difficulty === 'NORMAL' ? 2 : 1.15);
  const selected = [];
  const starts = new Map();
  for (const e of assigned) starts.set(e.timeMs, (starts.get(e.timeMs) || 0) + 1);

  const options = assigned
    .filter((e) => starts.get(e.timeMs) === 1 && motions.has(e.timeMs))
    .sort((a, b) => motions.get(b.timeMs).confidence - motions.get(a.timeMs).confidence);

  for (const entry of options) {
    const time = entry.timeMs;
    const origLane = entry.lane;
    const motion = motions.get(time);
    if (selected.some((at) => Math.abs(time - at) < spacing)) continue;

    if (motion.type === 'slide') {
      if (entry.type !== 'TAP' || (difficulty === 'EASY' && motion.confidence < 0.83)) continue;
    } else {
      if (difficulty === 'EASY' || (motion.turn && difficulty !== 'HARD' && difficulty !== 'MASTER') || motion.end - time < minimumHold) {
        continue;
      }
      if (motion.turn && (motion.turn - time < 135 || motion.end - motion.turn < 135)) continue;
    }

    const sameHand = (l) => l >= 0 && l < lanes && (entry.hand === 0 ? l <= center : l >= center);
    const placements = Array.from({ length: lanes }, (_, l) => l)
      .filter((l) => sameHand(l) && sameHand(l + motion.direction))
      .sort((a, b) => Math.abs(a - origLane) - Math.abs(b - origLane));

    for (const lane of placements) {
      const target = lane + motion.direction;
      const end = motion.type === 'slide' ? time : motion.end;
      const release = motion.type === 'slide' ? 105 : 55;

      const blocked = assigned.some((other) => {
        if (other === entry) return false;
        const oStart = other.timeMs;
        const oEnd = other.endMs ?? oStart;
        if (other.hand === entry.hand && oStart > time && oStart < Math.max(time + profile.handGap, end + release)) return true;
        if (other.hand === entry.hand && other.type === 'SLIDE' && oStart < time && time - oStart < 105) return true;
        // Check spatial lane overlap across time window
        if (oEnd + 55 >= time && oStart <= end + 55) {
          const minA = Math.min(lane, target);
          const maxA = Math.max(lane, target);
          const minB = Math.min(other.lane, other.slideTargetLane ?? other.lane);
          const maxB = Math.max(other.lane, other.slideTargetLane ?? other.lane);
          if (Math.max(minA, minB) <= Math.min(maxA, maxB)) return true;
        }
        return false;
      });
      if (blocked) continue;

      if (motion.type === 'slide') {
        entry.type = 'SLIDE';
        entry.lane = lane;
        entry.slideTargetLane = target;
        entry.endMs = undefined;
      } else {
        entry.type = 'HOLD_SLIDE';
        entry.lane = lane;
        entry.endMs = motion.end;
        entry.slideTargetLane = motion.turn ? lane : target;
        if (motion.turn) {
          entry.pathPoints = [
            { timeMs: time, lane },
            { timeMs: motion.turn, lane: target },
            { timeMs: motion.end, lane }
          ];
        } else {
          const pts = [{ timeMs: time, lane }];
          if (motion.ride && motion.ride - time >= 135) pts.push({ timeMs: motion.ride, lane });
          else if (motion.settle && motion.settle - time >= 135) pts.push({ timeMs: motion.settle, lane: target });
          pts.push({ timeMs: motion.end, lane: target });
          entry.pathPoints = pts;
        }
      }
      selected.push(time);
      break;
    }
  }

  return assigned.map((entry, idx) => {
    const out = {
      id: idx + 1,
      timeMs: Math.round(entry.timeMs),
      lane: entry.lane,
      type: entry.type,
      durationMs: entry.endMs !== undefined ? Math.max(120, Math.round(entry.endMs - entry.timeMs)) : 0,
      slideTargetLane: entry.slideTargetLane ?? entry.lane
    };
    if (entry.pathPoints) {
      out.pathPoints = entry.pathPoints.map((p) => ({ timeMs: Math.round(p.timeMs), lane: p.lane }));
    }
    return out;
  });
}

function sanitizeLaneOverlaps(notes, laneCount) {
  notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
  const cleaned = [];
  const laneOccupiedUntil = new Array(laneCount).fill(-1000);

  for (const n of notes) {
    if (n.timeMs < laneOccupiedUntil[n.lane]) continue;
    const target = n.slideTargetLane ?? n.lane;
    if (target !== n.lane && n.timeMs < laneOccupiedUntil[target]) {
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
    const busyEnd = n.timeMs + (n.durationMs || 0) + (n.durationMs > 0 ? 55 : 40);
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
    minHoldMs = 360,
    drumDoubles = true,
    quantize = false,
    autoDetectBpm = true
  } = options;

  const analysis = options._cachedAnalysis || analyzeAudioBuffer(audioBuffer, { bpm, offsetMs, autoDetectBpm, quantize });
  if (!analysis.candidates || analysis.candidates.length === 0) {
    return { notes: [], detectedBpm: bpm, detectedOffsetMs: offsetMs, analysis };
  }

  const diff = PROFILES[difficulty] ? difficulty : 'NORMAL';
  const beat = 60000 / analysis.bpm;
  const effectiveMinHold = Math.ceil(Math.max(minHoldMs, beat * (diff === 'EASY' ? 1.2 : 0.85)));

  const events = selectEvents(analysis, diff, densityScale);
  const chords = drumDoubles ? pickChords(events, diff, beat) : new Set();
  const assigned = assignLanes(events, chords, laneCount, diff, beat, analysis.offset, effectiveMinHold);
  const rawNotes = addMotions(assigned, events, laneCount, diff, beat, effectiveMinHold, onlyTapAndHold);
  const notes = sanitizeLaneOverlaps(rawNotes, laneCount);

  return {
    notes,
    detectedBpm: Math.round(analysis.bpm * 2) / 2,
    detectedOffsetMs: analysis.offset,
    analysis
  };
}
