import { createAstraZipPack, parseAstraZipPack } from './zip-utils.js';
import { generateAutoChart } from './auto-charter.js';
import { Preview3DEngine } from './preview3d.js';

const STORAGE_KEY = 'astra_echo_web_editor_v1';
const LANE_KEYS = {
  4: ['KeyD', 'KeyF', 'KeyJ', 'KeyK'],
  5: ['KeyD', 'KeyF', 'Space', 'KeyJ', 'KeyK'],
  6: ['KeyS', 'KeyD', 'KeyF', 'KeyJ', 'KeyK', 'KeyL']
};
const LANE_LABELS = {
  4: ['D', 'F', 'J', 'K'],
  5: ['D', 'F', 'Space', 'J', 'K'],
  6: ['S', 'D', 'F', 'J', 'K', 'L']
};

const ALL_DIFFS = ['EASY', 'NORMAL', 'HARD', 'MASTER'];

// ============================================================================
// State
// ============================================================================
const state = {
  chart: {
    songId: `web_${Date.now()}`,
    title: '星律回响 Demo',
    artist: 'ASTRA STUDIO',
    bpm: 128,
    durationMs: 32000,
    laneCount: 4,
    difficulty: 'NORMAL',
    onlyTapAndHold: false,
    notes: []
  },
  // Stores notes per `${laneCount}k_${difficulty}` so switching difficulties preserves/loads distinct charts
  chartsByKey: {},
  offsetMs: 0,
  snapDiv: 4,
  zoomPxPerSec: 240,
  showWaveform: true,
  tool: 'SELECT', // SELECT | TAP | HOLD | SLIDE | HOLD_SLIDE | ERASE
  selectedIds: new Set(),
  clipboard: [],
  undoStack: [],
  redoStack: [],

  // Playback
  audioCtx: null,
  audioBuffer: null,
  audioBytes: null,
  audioExt: 'wav',
  audioFileName: '内置合成电音示例 (Demo 128 BPM)',
  waveformPeaks: null, // Float32Array (one peak per 10ms)
  isPlaying: false,
  isRecording: false,
  playbackRate: 1.0,
  playStartAudioTime: 0,
  playStartOffsetMs: 0,
  currentTimeMs: 0,
  sourceNode: null,
  lastMetronomeBeat: -1,
  lastHitTickMs: -1,

  // Live recording active holds: Map<lane, { startMs }>
  activeRecLanes: new Map(),

  // Tap BPM timestamps
  tapBpmTimes: [],

  // Pointer interaction on timeline canvas
  drag: null
};

// ============================================================================
// DOM References
// ============================================================================
const $ = (id) => document.getElementById(id);
const canvas = $('timeline-canvas');
const ctx = canvas.getContext('2d');
const scrubber = $('timeline-scrubber');

// ============================================================================
// Utility Helpers
// ============================================================================
function formatTime(ms) {
  const clamped = Math.max(0, Math.round(ms));
  const mins = Math.floor(clamped / 60000);
  const secs = Math.floor((clamped % 60000) / 1000);
  const rem = clamped % 1000;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}.${String(rem).padStart(3, '0')}`;
}

let toastTimer = null;
function showToast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2600);
}

function nextNoteId() {
  let maxId = 0;
  for (const n of state.chart.notes) {
    if (n.id > maxId) maxId = n.id;
  }
  return maxId + 1;
}

function snapTimeMs(ms) {
  const clamped = Math.max(0, Math.min(state.chart.durationMs, ms));
  if (!state.snapDiv || state.snapDiv <= 0) return Math.round(clamped);
  const beatMs = 60000 / Math.max(40, state.chart.bpm);
  const step = beatMs / state.snapDiv;
  const snapped = Math.round((clamped - state.offsetMs) / step) * step + state.offsetMs;
  return Math.max(0, Math.min(state.chart.durationMs, Math.round(snapped)));
}

function ensureAudioContext() {
  if (!state.audioCtx) {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    state.audioCtx = new AudioCtx();
  }
  if (state.audioCtx.state === 'suspended') {
    state.audioCtx.resume();
  }
  return state.audioCtx;
}

function playSynthClick(freq = 1000, duration = 0.035, gainVal = 0.16, type = 'triangle') {
  try {
    const ac = ensureAudioContext();
    const osc = ac.createOscillator();
    const gain = ac.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, ac.currentTime);
    gain.gain.setValueAtTime(gainVal, ac.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ac.currentTime + duration);
    osc.connect(gain);
    gain.connect(ac.destination);
    osc.start();
    osc.stop(ac.currentTime + duration);
  } catch (_) {}
}

// ============================================================================
// Undo / Redo & Autosave
// ============================================================================
function pushUndo() {
  const snapshot = JSON.stringify(state.chart.notes);
  state.undoStack.push(snapshot);
  if (state.undoStack.length > 60) state.undoStack.shift();
  state.redoStack.length = 0;
  updateUndoButtons();
  scheduleAutosave();
}

function undo() {
  if (state.undoStack.length === 0) return;
  state.redoStack.push(JSON.stringify(state.chart.notes));
  state.chart.notes = JSON.parse(state.undoStack.pop());
  state.selectedIds.clear();
  updateUndoButtons();
  refreshInspector();
  updateStats();
  showToast('已撤销');
}

function redo() {
  if (state.redoStack.length === 0) return;
  state.undoStack.push(JSON.stringify(state.chart.notes));
  state.chart.notes = JSON.parse(state.redoStack.pop());
  state.selectedIds.clear();
  updateUndoButtons();
  refreshInspector();
  updateStats();
  showToast('已重做');
}

function updateUndoButtons() {
  $('btn-undo').disabled = state.undoStack.length === 0;
  $('btn-redo').disabled = state.redoStack.length === 0;
}

function currentChartKey(laneCount = state.chart.laneCount, difficulty = state.chart.difficulty) {
  return `${laneCount}k_${difficulty}`;
}

function saveCurrentChartSlot() {
  state.chartsByKey[currentChartKey()] = state.chart.notes.map((n) => ({
    ...n,
    ...(n.pathPoints ? { pathPoints: n.pathPoints.map((p) => ({ ...p })) } : {})
  }));
}

let autosaveTimer = null;
function scheduleAutosave() {
  saveCurrentChartSlot();
  $('autosave-status').textContent = '保存中…';
  clearTimeout(autosaveTimer);
  autosaveTimer = setTimeout(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        chart: state.chart,
        chartsByKey: state.chartsByKey,
        offsetMs: state.offsetMs
      }));
      $('autosave-status').textContent = '已自动保存';
    } catch (_) {
      $('autosave-status').textContent = '本地空间不足';
    }
  }, 500);
}

// ============================================================================
// Synthesized Demo Audio + WAV Encoder (so ZIP export works out of the box!)
// ============================================================================
function encodeAudioBufferToWav(audioBuffer) {
  const sampleRate = audioBuffer.sampleRate;
  const ch0 = audioBuffer.getChannelData(0);
  const numSamples = ch0.length;
  const buffer = new ArrayBuffer(44 + numSamples * 2);
  const view = new DataView(buffer);

  const writeStr = (off, str) => {
    for (let i = 0; i < str.length; i++) view.setUint8(off + i, str.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + numSamples * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // Mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeStr(36, 'data');
  view.setUint32(40, numSamples * 2, true);

  let offset = 44;
  for (let i = 0; i < numSamples; i++) {
    const s = Math.max(-1, Math.min(1, ch0[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    offset += 2;
  }
  return new Uint8Array(buffer);
}

async function loadDemoTrack(overwriteNotes = true) {
  stopPlayback();
  const sampleRate = 22050;
  const bpm = 128;
  const beatSec = 60 / bpm;
  const totalBeats = 64; // 30 seconds
  const durationSec = totalBeats * beatSec;
  const length = Math.floor(sampleRate * durationSec);
  const ac = ensureAudioContext();
  const buf = ac.createBuffer(1, length, sampleRate);
  const data = buf.getChannelData(0);

  // Pentatonic synth arpeggio + four-on-the-floor kick + hi-hat
  const scaleHz = [261.63, 293.66, 329.63, 392.00, 440.00, 523.25, 587.33, 659.25];
  for (let i = 0; i < length; i++) {
    const t = i / sampleRate;
    const beatPos = t / beatSec;
    const beatFrac = beatPos - Math.floor(beatPos);
    const stepIdx = Math.floor(beatPos * 2);
    const stepFrac = (beatPos * 2) - stepIdx;

    // Kick drum on every beat
    const kickEnv = Math.exp(-beatFrac * 18);
    const kickFreq = 135 * Math.exp(-beatFrac * 22) + 46;
    const kick = Math.sin(2 * Math.PI * kickFreq * beatFrac * beatSec) * kickEnv * 0.45;

    // Synth arp on 1/2 beats
    const noteHz = scaleHz[(stepIdx * 3 + Math.floor(beatPos / 8)) % scaleHz.length];
    const arpEnv = Math.exp(-stepFrac * 7.5);
    const arp = Math.sin(2 * Math.PI * noteHz * t) * arpEnv * 0.24;

    // Subtle pad chord
    const pad = Math.sin(2 * Math.PI * 130.81 * t) * 0.1;

    data[i] = Math.max(-0.95, Math.min(0.95, kick + arp + pad));
  }

  state.audioBuffer = buf;
  state.audioBytes = encodeAudioBufferToWav(buf);
  state.audioExt = 'wav';
  state.audioFileName = '内置合成电音示例 (128 BPM Demo)';
  state.chart.durationMs = Math.round(durationSec * 1000);
  state.chart.bpm = bpm;
  state.offsetMs = 0;
  computeWaveformPeaks(buf);

  if (overwriteNotes) {
    state.chart.title = '星律回响 Demo';
    state.chart.artist = 'ASTRA STUDIO';
    state.chart.notes = buildDemoNotes(bpm, state.chart.laneCount);
  }

  syncFormFromState();
  updateAudioDisplay();
  updateStats();
  refreshInspector();
  showToast('已加载内置 128 BPM 电音示例曲与演示谱面');
}

function buildDemoNotes(bpm, laneCount) {
  const beatMs = 60000 / bpm;
  const notes = [];
  let id = 1;
  for (let b = 2; b < 60; b++) {
    const t = Math.round(b * beatMs);
    const lane = b % laneCount;
    if (b % 8 === 0) {
      // HOLD note
      notes.push({ id: id++, timeMs: t, lane, type: 'HOLD', durationMs: Math.round(beatMs * 1.5), slideTargetLane: lane });
    } else if (b % 8 === 4 && !state.chart.onlyTapAndHold) {
      // SLIDE note
      const target = (lane + 1) % laneCount;
      notes.push({ id: id++, timeMs: t, lane, type: 'SLIDE', durationMs: 0, slideTargetLane: target });
    } else if (b % 12 === 6 && !state.chart.onlyTapAndHold) {
      // HOLD_SLIDE bent rail
      const dur = Math.round(beatMs * 2);
      const target = (lane + 1) % laneCount;
      notes.push({
        id: id++,
        timeMs: t,
        lane,
        type: 'HOLD_SLIDE',
        durationMs: dur,
        slideTargetLane: target,
        pathPoints: [
          { timeMs: t, lane },
          { timeMs: t + Math.round(dur * 0.45), lane },
          { timeMs: t + Math.round(dur * 0.58), lane: target },
          { timeMs: t + dur, lane: target }
        ]
      });
    } else {
      notes.push({ id: id++, timeMs: t, lane, type: 'TAP', durationMs: 0, slideTargetLane: lane });
      if (b % 4 === 2) {
        const halfT = Math.round((b + 0.5) * beatMs);
        notes.push({ id: id++, timeMs: halfT, lane: (lane + 2) % laneCount, type: 'TAP', durationMs: 0, slideTargetLane: (lane + 2) % laneCount });
      }
    }
  }
  return notes;
}

function computeWaveformPeaks(audioBuffer) {
  const stepMs = 10;
  const stepSamples = Math.max(1, Math.floor((audioBuffer.sampleRate * stepMs) / 1000));
  const totalBins = Math.ceil(audioBuffer.length / stepSamples);
  const peaks = new Float32Array(totalBins);
  const ch = audioBuffer.getChannelData(0);
  for (let b = 0; b < totalBins; b++) {
    const start = b * stepSamples;
    const end = Math.min(ch.length, start + stepSamples);
    let maxAmp = 0;
    for (let i = start; i < end; i++) {
      const a = Math.abs(ch[i]);
      if (a > maxAmp) maxAmp = a;
    }
    peaks[b] = maxAmp;
  }
  state.waveformPeaks = peaks;
}

// ============================================================================
// Audio Playback Engine
// ============================================================================
function getCurrentTimeMs() {
  if (!state.isPlaying) return state.currentTimeMs;
  const ac = state.audioCtx;
  if (!ac) return state.currentTimeMs;
  const elapsedSec = (ac.currentTime - state.playStartAudioTime) * state.playbackRate;
  const now = state.playStartOffsetMs + elapsedSec * 1000;
  if (now >= state.chart.durationMs) {
    stopPlayback(state.chart.durationMs);
    return state.currentTimeMs;
  }
  return now;
}

function startPlayback() {
  if (state.isPlaying) return;
  const ac = ensureAudioContext();
  if (state.currentTimeMs >= state.chart.durationMs - 50) {
    state.currentTimeMs = 0;
  }

  state.playStartOffsetMs = state.currentTimeMs;
  state.playStartAudioTime = ac.currentTime;
  state.isPlaying = true;
  state.lastMetronomeBeat = Math.floor((state.currentTimeMs - state.offsetMs) / (60000 / state.chart.bpm));
  state.lastHitTickMs = state.currentTimeMs;

  if (state.audioBuffer) {
    const src = ac.createBufferSource();
    src.buffer = state.audioBuffer;
    src.playbackRate.value = state.playbackRate;
    src.connect(ac.destination);
    src.start(0, Math.max(0, state.currentTimeMs / 1000));
    state.sourceNode = src;
  }
  $('btn-play').textContent = '⏸ 暂停';
}

function stopPlayback(finalMs = null) {
  if (!state.isPlaying) return;
  if (typeof finalMs === 'number') {
    state.currentTimeMs = Math.max(0, Math.min(state.chart.durationMs, finalMs));
  } else if (state.audioCtx) {
    const elapsedSec = (state.audioCtx.currentTime - state.playStartAudioTime) * state.playbackRate;
    state.currentTimeMs = Math.max(0, Math.min(state.chart.durationMs, state.playStartOffsetMs + elapsedSec * 1000));
  }
  state.isPlaying = false;
  if (state.sourceNode) {
    try { state.sourceNode.stop(); } catch (_) {}
    state.sourceNode.disconnect();
    state.sourceNode = null;
  }
  $('btn-play').textContent = '▶ 播放';
  if ($('btn-preview-toggle')) $('btn-preview-toggle').textContent = '▶ 继续';
}

function togglePlayback() {
  if (state.isPlaying) stopPlayback();
  else startPlayback();
}

function seekTo(ms) {
  const wasPlaying = state.isPlaying;
  if (wasPlaying) stopPlayback();
  state.currentTimeMs = Math.max(0, Math.min(state.chart.durationMs, ms));
  if (wasPlaying) startPlayback();
}

// ============================================================================
// File Loading (Audio, JSON, Android .ZIP Pack)
// ============================================================================
async function handleFileLoad(file) {
  if (!file) return;
  const ext = (file.name.split('.').pop() || '').toLowerCase();

  try {
    if (ext === 'json') {
      const text = await file.text();
      const parsed = JSON.parse(text);
      applyImportedChart(parsed);
      showToast(`已导入 JSON 谱面：${state.chart.title} (${state.chart.notes.length} 音符)`);
      return;
    }

    if (ext === 'zip') {
      const ab = await file.arrayBuffer();
      const pack = await parseAstraZipPack(ab);
      if (pack.audioBytes) {
        await decodeAndSetAudio(pack.audioBytes, pack.audioExt, file.name);
      }
      if (pack.chart) {
        applyImportedChart(pack.chart);
      }
      showToast(`已导入 Android 谱面包：${state.chart.title}`);
      return;
    }

    // Audio or MP4 video
    const ab = await file.arrayBuffer();
    const bytes = new Uint8Array(ab);
    await decodeAndSetAudio(bytes, ext || 'mp3', file.name);
    state.chart.songId = `web_${Date.now()}`;
    state.chart.title = file.name.replace(/\.[^.]+$/, '');
    state.chartsByKey = {};
    syncFormFromState();
    showToast(`音频已加载：${file.name} (${formatTime(state.chart.durationMs)})`);
  } catch (err) {
    console.error(err);
    showToast(`导入失败：${err.message || err}`);
  }
}

async function decodeAndSetAudio(uint8Bytes, ext, displayName) {
  stopPlayback();
  const ac = ensureAudioContext();
  // Slice copy because decodeAudioData detaches the ArrayBuffer
  const copy = uint8Bytes.buffer.slice(uint8Bytes.byteOffset, uint8Bytes.byteOffset + uint8Bytes.byteLength);
  const decoded = await ac.decodeAudioData(copy);
  state.audioBuffer = decoded;
  state.audioBytes = uint8Bytes;
  state.audioExt = ext;
  state.audioFileName = displayName;
  state.chart.durationMs = Math.round(decoded.duration * 1000);
  state.currentTimeMs = 0;
  computeWaveformPeaks(decoded);
  updateAudioDisplay();
}

function applyImportedChart(raw) {
  pushUndo();
  state.chart = {
    songId: raw.songId || `web_${Date.now()}`,
    title: raw.title || '未命名曲目',
    artist: raw.artist || 'ASTRA STUDIO',
    bpm: Number(raw.bpm) || 128,
    durationMs: state.audioBuffer ? Math.round(state.audioBuffer.duration * 1000) : (Number(raw.durationMs) || 60000),
    laneCount: [4, 5, 6].includes(Number(raw.laneCount)) ? Number(raw.laneCount) : 4,
    difficulty: raw.difficulty || 'NORMAL',
    onlyTapAndHold: Boolean(raw.onlyTapAndHold),
    notes: Array.isArray(raw.notes) ? raw.notes.map((n, idx) => ({
      id: Number(n.id) || (idx + 1),
      timeMs: Math.max(0, Math.round(Number(n.timeMs) || 0)),
      lane: Math.max(0, Math.min((Number(raw.laneCount) || 4) - 1, Number(n.lane) || 0)),
      type: n.type || 'TAP',
      durationMs: Math.max(0, Math.round(Number(n.durationMs) || 0)),
      slideTargetLane: n.slideTargetLane != null ? Number(n.slideTargetLane) : (Number(n.lane) || 0),
      ...(Array.isArray(n.pathPoints) ? { pathPoints: n.pathPoints } : {})
    })) : []
  };
  state.selectedIds.clear();
  syncFormFromState();
  rebuildLivePads();
  updateStats();
  refreshInspector();
  scheduleAutosave();
}

function updateAudioDisplay() {
  $('audio-file-name').textContent = state.audioFileName;
  $('audio-file-meta').textContent = `时长 ${formatTime(state.chart.durationMs)} · 点击或拖入替换音频`;
  $('time-total').textContent = formatTime(state.chart.durationMs);
}

// ============================================================================
// Timeline Geometry & Rendering
// ============================================================================
function getTimelineLayout() {
  const rect = canvas.getBoundingClientRect();
  const w = rect.width;
  const h = rect.height;
  const waveW = state.showWaveform ? 116 : 0;
  const rulerW = 54;
  const leftMargin = rulerW + waveW;
  const rightMargin = 24;
  const laneAreaW = Math.max(180, Math.min(540, w - leftMargin - rightMargin));
  const lanesLeft = leftMargin + Math.max(0, (w - leftMargin - rightMargin - laneAreaW) / 2);
  const laneW = laneAreaW / state.chart.laneCount;
  const judgeY = h - 72;
  return { w, h, rulerW, waveW, lanesLeft, laneAreaW, laneW, judgeY };
}

function timeToY(timeMs, nowMs, judgeY) {
  return judgeY - ((timeMs - nowMs) / 1000) * state.zoomPxPerSec;
}

function yToTime(y, nowMs, judgeY) {
  return nowMs + ((judgeY - y) / state.zoomPxPerSec) * 1000;
}

function xToLane(x, layout) {
  const rel = (x - layout.lanesLeft) / layout.laneW;
  if (rel < 0 || rel >= state.chart.laneCount) return -1;
  return Math.floor(rel);
}

function hitTestNote(x, y, nowMs, layout) {
  const lane = xToLane(x, layout);
  if (lane < 0) return null;

  // Iterate reverse so topmost rendered note wins
  for (let i = state.chart.notes.length - 1; i >= 0; i--) {
    const n = state.chart.notes[i];
    const headY = timeToY(n.timeMs, nowMs, layout.judgeY);
    const tailY = timeToY(n.timeMs + (n.durationMs || 0), nowMs, layout.judgeY);

    if (n.lane === lane && Math.abs(y - headY) <= 13) {
      return n;
    }
    if ((n.type === 'HOLD' || n.type === 'HOLD_SLIDE') && n.durationMs > 0) {
      if (y <= headY + 10 && y >= tailY - 10) {
        if (n.lane === lane || n.slideTargetLane === lane) return n;
      }
    }
  }
  return null;
}

function renderTimeline() {
  const rect = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const targetW = Math.max(1, Math.floor(rect.width * dpr));
  const targetH = Math.max(1, Math.floor(rect.height * dpr));
  if (canvas.width !== targetW || canvas.height !== targetH) {
    canvas.width = targetW;
    canvas.height = targetH;
  }

  const nowMs = getCurrentTimeMs();
  state.currentTimeMs = nowMs;

  // Audio feedback ticks while playing
  if (state.isPlaying) {
    if ($('chk-metronome').checked) {
      const beatMs = 60000 / Math.max(40, state.chart.bpm);
      const curBeat = Math.floor((nowMs - state.offsetMs) / beatMs);
      if (curBeat > state.lastMetronomeBeat && curBeat >= 0) {
        state.lastMetronomeBeat = curBeat;
        playSynthClick(curBeat % 4 === 0 ? 1320 : 880, 0.03, 0.14, 'triangle');
      }
    }
    if ($('chk-hitsound').checked) {
      for (const n of state.chart.notes) {
        if (n.timeMs > state.lastHitTickMs && n.timeMs <= nowMs) {
          playSynthClick(n.type === 'SLIDE' ? 1480 : 1040, 0.028, 0.12, 'sine');
          break;
        }
      }
      state.lastHitTickMs = nowMs;
    }
  }

  // Update time display & scrubber
  $('time-current').textContent = formatTime(nowMs);
  const ratio = state.chart.durationMs > 0 ? nowMs / state.chart.durationMs : 0;
  scrubber.value = String(Math.round(ratio * 1000));

  const layout = getTimelineLayout();
  const { w, h, rulerW, waveW, lanesLeft, laneAreaW, laneW, judgeY } = layout;

  ctx.save();
  ctx.scale(dpr, dpr);

  // Background
  ctx.fillStyle = '#060914';
  ctx.fillRect(0, 0, w, h);

  // Left Waveform Track
  if (state.showWaveform && state.waveformPeaks) {
    ctx.fillStyle = '#090e1f';
    ctx.fillRect(rulerW, 0, waveW, h);
    const centerX = rulerW + waveW / 2;
    const minMs = Math.max(0, yToTime(h, nowMs, judgeY));
    const maxMs = Math.min(state.chart.durationMs, yToTime(0, nowMs, judgeY));

    ctx.strokeStyle = 'rgba(56, 242, 255, 0.55)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    const startBin = Math.max(0, Math.floor(minMs / 10));
    const endBin = Math.min(state.waveformPeaks.length - 1, Math.ceil(maxMs / 10));
    for (let b = startBin; b <= endBin; b++) {
      const amp = state.waveformPeaks[b];
      if (amp < 0.01) continue;
      const y = timeToY(b * 10, nowMs, judgeY);
      const half = amp * (waveW * 0.44);
      ctx.moveTo(centerX - half, y);
      ctx.lineTo(centerX + half, y);
    }
    ctx.stroke();
  }

  // Lane background
  ctx.fillStyle = '#0b1024';
  ctx.fillRect(lanesLeft, 0, laneAreaW, h);

  // Beat Grid Lines
  const beatMs = 60000 / Math.max(40, state.chart.bpm);
  const div = Math.max(1, state.snapDiv || 4);
  const stepMs = beatMs / div;
  const visMinMs = Math.max(0, yToTime(h, nowMs, judgeY));
  const visMaxMs = Math.min(state.chart.durationMs, yToTime(0, nowMs, judgeY));
  const firstStep = Math.floor((visMinMs - state.offsetMs) / stepMs);
  const lastStep = Math.ceil((visMaxMs - state.offsetMs) / stepMs);

  ctx.font = '11px "Cascadia Code", monospace';
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';

  for (let s = firstStep; s <= lastStep; s++) {
    const t = state.offsetMs + s * stepMs;
    if (t < 0 || t > state.chart.durationMs) continue;
    const y = timeToY(t, nowMs, judgeY);
    const isMeasure = (s % (div * 4) === 0);
    const isBeat = (s % div === 0);

    ctx.strokeStyle = isMeasure
      ? 'rgba(255, 209, 102, 0.55)'
      : isBeat
        ? 'rgba(130, 170, 255, 0.28)'
        : 'rgba(130, 170, 255, 0.1)';
    ctx.lineWidth = isMeasure ? 1.5 : 1;
    ctx.beginPath();
    ctx.moveTo(lanesLeft, y);
    ctx.lineTo(lanesLeft + laneAreaW, y);
    ctx.stroke();

    if (isMeasure) {
      const barNum = Math.round(s / (div * 4)) + 1;
      ctx.fillStyle = '#ffd166';
      ctx.fillText(`#${barNum}`, rulerW - 8, y);
    } else if (isBeat) {
      ctx.fillStyle = '#697ea3';
      ctx.fillText(`${(t / 1000).toFixed(1)}s`, rulerW - 8, y);
    }
  }

  // Lane vertical dividers & labels
  const keys = LANE_LABELS[state.chart.laneCount] || LANE_LABELS[4];
  for (let l = 0; l <= state.chart.laneCount; l++) {
    const x = lanesLeft + l * laneW;
    ctx.strokeStyle = (l === 0 || l === state.chart.laneCount)
      ? 'rgba(56, 242, 255, 0.45)'
      : 'rgba(120, 160, 255, 0.2)';
    ctx.lineWidth = (l === 0 || l === state.chart.laneCount) ? 2 : 1;
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, h);
    ctx.stroke();

    if (l < state.chart.laneCount) {
      ctx.fillStyle = 'rgba(155, 176, 211, 0.75)';
      ctx.textAlign = 'center';
      ctx.fillText(`L${l + 1} (${keys[l]})`, x + laneW / 2, h - 22);
    }
  }

  // Draw Notes
  for (const n of state.chart.notes) {
    const endMs = n.timeMs + (n.durationMs || 0);
    if (endMs < visMinMs - 200 || n.timeMs > visMaxMs + 200) continue;
    drawTimelineNote(n, nowMs, layout, state.selectedIds.has(n.id));
  }

  // Draw active drag preview (creation or box-select)
  if (state.drag) {
    if (state.drag.mode === 'create' && state.drag.previewNote) {
      ctx.save();
      ctx.globalAlpha = 0.7;
      drawTimelineNote(state.drag.previewNote, nowMs, layout, true);
      ctx.restore();
    } else if (state.drag.mode === 'box') {
      const bx = Math.min(state.drag.startX, state.drag.curX);
      const by = Math.min(state.drag.startY, state.drag.curY);
      const bw = Math.abs(state.drag.curX - state.drag.startX);
      const bh = Math.abs(state.drag.curY - state.drag.startY);
      ctx.fillStyle = 'rgba(56, 242, 255, 0.14)';
      ctx.strokeStyle = '#38f2ff';
      ctx.lineWidth = 1.2;
      ctx.fillRect(bx, by, bw, bh);
      ctx.strokeRect(bx, by, bw, bh);
    }
  }

  // Judge / Playhead Line
  ctx.save();
  ctx.strokeStyle = state.isRecording ? '#ff2e55' : '#38f2ff';
  ctx.lineWidth = 2.5;
  ctx.shadowColor = state.isRecording ? '#ff2e55' : '#38f2ff';
  ctx.shadowBlur = 12;
  ctx.beginPath();
  ctx.moveTo(rulerW, judgeY);
  ctx.lineTo(lanesLeft + laneAreaW, judgeY);
  ctx.stroke();
  ctx.restore();

  ctx.restore();
  requestAnimationFrame(renderTimeline);
}

function drawTimelineNote(n, nowMs, layout, selected) {
  const { lanesLeft, laneW, judgeY } = layout;
  const x = lanesLeft + n.lane * laneW;
  const headY = timeToY(n.timeMs, nowMs, judgeY);
  const pad = 6;
  const noteW = laneW - pad * 2;

  // Hold / Hold-Slide body
  if ((n.type === 'HOLD' || n.type === 'HOLD_SLIDE') && n.durationMs > 0) {
    const pts = (n.type === 'HOLD_SLIDE' && Array.isArray(n.pathPoints) && n.pathPoints.length >= 2)
      ? n.pathPoints
      : [{ timeMs: n.timeMs, lane: n.lane }, { timeMs: n.timeMs + n.durationMs, lane: n.slideTargetLane ?? n.lane }];

    ctx.fillStyle = n.type === 'HOLD_SLIDE' ? 'rgba(255, 209, 102, 0.36)' : 'rgba(100, 168, 255, 0.36)';
    ctx.strokeStyle = n.type === 'HOLD_SLIDE' ? '#ffd166' : '#64a8ff';
    ctx.lineWidth = 1.5;

    for (let i = 0; i < pts.length - 1; i++) {
      const ax = lanesLeft + pts[i].lane * laneW + pad + 4;
      const ay = timeToY(pts[i].timeMs, nowMs, judgeY);
      const bx = lanesLeft + pts[i + 1].lane * laneW + pad + 4;
      const by = timeToY(pts[i + 1].timeMs, nowMs, judgeY);
      const bw = noteW - 8;
      ctx.beginPath();
      ctx.moveTo(ax, ay);
      ctx.lineTo(ax + bw, ay);
      ctx.lineTo(bx + bw, by);
      ctx.lineTo(bx, by);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    }
  }

  // Slide connector arrow
  if (n.type === 'SLIDE' && n.slideTargetLane != null && n.slideTargetLane !== n.lane) {
    const tx = lanesLeft + n.slideTargetLane * laneW + laneW / 2;
    const sx = x + laneW / 2;
    ctx.strokeStyle = '#ff3b8d';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.moveTo(sx, headY);
    ctx.lineTo(tx, headY);
    ctx.stroke();
  }

  // Note head block
  let fill = '#38f2ff';
  if (n.type === 'HOLD') fill = '#64a8ff';
  if (n.type === 'SLIDE') fill = '#ff3b8d';
  if (n.type === 'HOLD_SLIDE') fill = '#ffd166';

  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.roundRect(x + pad, headY - 7, noteW, 14, 5);
  ctx.fill();

  if (n.type === 'SLIDE') {
    const dir = (n.slideTargetLane ?? n.lane) >= n.lane ? '→' : '←';
    ctx.fillStyle = '#070a16';
    ctx.font = 'bold 11px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(dir, x + laneW / 2, headY);
  }

  if (selected) {
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.roundRect(x + pad - 2, headY - 9, noteW + 4, 18, 6);
    ctx.stroke();
  }
}

// ============================================================================
// Canvas Mouse / Pointer Editing
// ============================================================================
canvas.addEventListener('contextmenu', (e) => {
  e.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const y = e.clientY - rect.top;
  const layout = getTimelineLayout();
  const hit = hitTestNote(x, y, getCurrentTimeMs(), layout);
  if (hit) {
    pushUndo();
    state.chart.notes = state.chart.notes.filter((n) => n.id !== hit.id);
    state.selectedIds.delete(hit.id);
    refreshInspector();
    updateStats();
  }
});

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  if (e.ctrlKey) {
    const delta = e.deltaY > 0 ? -20 : 20;
    state.zoomPxPerSec = Math.max(90, Math.min(600, state.zoomPxPerSec + delta));
    $('range-zoom').value = String(state.zoomPxPerSec);
    $('zoom-label').textContent = String(state.zoomPxPerSec);
  } else {
    const deltaMs = (-e.deltaY / state.zoomPxPerSec) * 1000 * 0.8;
    seekTo(getCurrentTimeMs() + deltaMs);
  }
}, { passive: false });

canvas.addEventListener('pointerdown', (e) => {
  if (e.button !== 0) return;
  const rect = canvas.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const y = e.clientY - rect.top;
  const layout = getTimelineLayout();
  const nowMs = getCurrentTimeMs();
  const lane = xToLane(x, layout);
  const rawTime = yToTime(y, nowMs, layout.judgeY);
  const snappedTime = snapTimeMs(rawTime);

  canvas.setPointerCapture(e.pointerId);

  if (state.tool === 'ERASE') {
    const hit = hitTestNote(x, y, nowMs, layout);
    if (hit) {
      pushUndo();
      state.chart.notes = state.chart.notes.filter((n) => n.id !== hit.id);
      state.selectedIds.delete(hit.id);
      refreshInspector();
      updateStats();
    }
    state.drag = { mode: 'erase' };
    return;
  }

  if (state.tool === 'SELECT') {
    const hit = hitTestNote(x, y, nowMs, layout);
    if (hit) {
      if (!e.ctrlKey && !e.shiftKey && !state.selectedIds.has(hit.id)) {
        state.selectedIds.clear();
      }
      state.selectedIds.add(hit.id);
      refreshInspector();
      const origins = new Map();
      for (const n of state.chart.notes) {
        if (state.selectedIds.has(n.id)) {
          origins.set(n.id, {
            timeMs: n.timeMs,
            lane: n.lane,
            slideTargetLane: n.slideTargetLane,
            pathPoints: n.pathPoints ? n.pathPoints.map((p) => ({ ...p })) : null
          });
        }
      }
      state.drag = { mode: 'move', startX: x, startY: y, startTime: rawTime, startLane: lane, origins, pushed: false };
    } else {
      if (!e.ctrlKey && !e.shiftKey) state.selectedIds.clear();
      refreshInspector();
      state.drag = { mode: 'box', startX: x, startY: y, curX: x, curY: y };
    }
    return;
  }

  // Creation tools: TAP / HOLD / SLIDE / HOLD_SLIDE
  if (lane < 0) return;
  if ((state.tool === 'SLIDE' || state.tool === 'HOLD_SLIDE') && state.chart.onlyTapAndHold) {
    showToast('当前已勾选「仅点按 + 长条模式」，请取消勾选后再添加滑键');
    return;
  }

  const previewNote = {
    id: nextNoteId(),
    timeMs: snappedTime,
    lane,
    type: state.tool,
    durationMs: state.tool === 'HOLD' || state.tool === 'HOLD_SLIDE' ? 300 : 0,
    slideTargetLane: state.tool === 'SLIDE' || state.tool === 'HOLD_SLIDE'
      ? Math.min(state.chart.laneCount - 1, lane < state.chart.laneCount - 1 ? lane + 1 : lane - 1)
      : lane
  };

  if (state.tool === 'TAP') {
    pushUndo();
    state.chart.notes.push(previewNote);
    state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
    state.selectedIds = new Set([previewNote.id]);
    playSynthClick(1040, 0.03, 0.12);
    refreshInspector();
    updateStats();
    return;
  }

  state.drag = { mode: 'create', previewNote, startLane: lane, startTime: snappedTime };
});

canvas.addEventListener('pointermove', (e) => {
  if (!state.drag) return;
  const rect = canvas.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const y = e.clientY - rect.top;
  const layout = getTimelineLayout();
  const nowMs = getCurrentTimeMs();
  const lane = Math.max(0, Math.min(state.chart.laneCount - 1, Math.floor((x - layout.lanesLeft) / layout.laneW)));
  const rawTime = yToTime(y, nowMs, layout.judgeY);

  if (state.drag.mode === 'erase') {
    const hit = hitTestNote(x, y, nowMs, layout);
    if (hit) {
      pushUndo();
      state.chart.notes = state.chart.notes.filter((n) => n.id !== hit.id);
      state.selectedIds.delete(hit.id);
      refreshInspector();
      updateStats();
    }
    return;
  }

  if (state.drag.mode === 'box') {
    state.drag.curX = x;
    state.drag.curY = y;
    return;
  }

  if (state.drag.mode === 'move') {
    const dt = snapTimeMs(rawTime) - snapTimeMs(state.drag.startTime);
    const dl = lane - state.drag.startLane;
    if ((dt !== 0 || dl !== 0) && !state.drag.pushed) {
      pushUndo();
      state.drag.pushed = true;
    }
    if (state.drag.pushed) {
      for (const n of state.chart.notes) {
        const orig = state.drag.origins.get(n.id);
        if (!orig) continue;
        n.timeMs = Math.max(0, Math.min(state.chart.durationMs, orig.timeMs + dt));
        n.lane = Math.max(0, Math.min(state.chart.laneCount - 1, orig.lane + dl));
        n.slideTargetLane = Math.max(0, Math.min(state.chart.laneCount - 1, (orig.slideTargetLane ?? orig.lane) + dl));
        if (orig.pathPoints) {
          n.pathPoints = orig.pathPoints.map((p) => ({
            timeMs: Math.max(0, p.timeMs + dt),
            lane: Math.max(0, Math.min(state.chart.laneCount - 1, p.lane + dl))
          }));
        }
      }
      refreshInspector();
    }
    return;
  }

  if (state.drag.mode === 'create') {
    const n = state.drag.previewNote;
    const endT = snapTimeMs(rawTime);
    if (n.type === 'HOLD') {
      n.durationMs = Math.max(120, endT - n.timeMs);
    } else if (n.type === 'SLIDE') {
      if (lane !== n.lane) n.slideTargetLane = lane;
    } else if (n.type === 'HOLD_SLIDE') {
      n.durationMs = Math.max(200, endT - n.timeMs);
      n.slideTargetLane = lane;
      const mid1 = n.timeMs + Math.round(n.durationMs * 0.45);
      const mid2 = n.timeMs + Math.round(n.durationMs * 0.58);
      n.pathPoints = [
        { timeMs: n.timeMs, lane: n.lane },
        { timeMs: mid1, lane: n.lane },
        { timeMs: mid2, lane },
        { timeMs: n.timeMs + n.durationMs, lane }
      ];
    }
  }
});

canvas.addEventListener('pointerup', () => {
  if (!state.drag) return;
  const d = state.drag;
  state.drag = null;

  if (d.mode === 'box') {
    const layout = getTimelineLayout();
    const nowMs = getCurrentTimeMs();
    const minX = Math.min(d.startX, d.curX);
    const maxX = Math.max(d.startX, d.curX);
    const minY = Math.min(d.startY, d.curY);
    const maxY = Math.max(d.startY, d.curY);
    for (const n of state.chart.notes) {
      const nx = layout.lanesLeft + (n.lane + 0.5) * layout.laneW;
      const ny = timeToY(n.timeMs, nowMs, layout.judgeY);
      if (nx >= minX && nx <= maxX && ny >= minY && ny <= maxY) {
        state.selectedIds.add(n.id);
      }
    }
    refreshInspector();
    return;
  }

  if (d.mode === 'move' && d.pushed) {
    state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
    updateStats();
    scheduleAutosave();
    return;
  }

  if (d.mode === 'create' && d.previewNote) {
    pushUndo();
    state.chart.notes.push(d.previewNote);
    state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
    state.selectedIds = new Set([d.previewNote.id]);
    playSynthClick(1160, 0.035, 0.13);
    refreshInspector();
    updateStats();
  }
});

// ============================================================================
// Inspector, Stats & Live Pads
// ============================================================================
function refreshInspector() {
  const count = state.selectedIds.size;
  $('selection-count').textContent = count > 0 ? `已选 ${count} 个` : '未选中';

  if (count === 0) {
    $('inspector-empty').hidden = false;
    $('inspector-form').hidden = true;
    return;
  }

  $('inspector-empty').hidden = true;
  $('inspector-form').hidden = false;
  const first = state.chart.notes.find((n) => state.selectedIds.has(n.id));
  if (!first) return;
  $('insp-type').value = first.type;
  $('insp-time').value = String(first.timeMs);
  $('insp-lane').value = String(first.lane);
  $('insp-lane').max = String(state.chart.laneCount - 1);
  $('insp-duration').value = String(first.durationMs || 0);
  $('insp-target-lane').value = String(first.slideTargetLane ?? first.lane);
  $('insp-target-lane').max = String(state.chart.laneCount - 1);
}

function updateStats() {
  const notes = state.chart.notes;
  let tap = 0, hold = 0, slide = 0, holdSlide = 0;
  const laneCounts = new Array(state.chart.laneCount).fill(0);
  for (const n of notes) {
    if (n.type === 'TAP') tap++;
    else if (n.type === 'HOLD') hold++;
    else if (n.type === 'SLIDE') slide++;
    else if (n.type === 'HOLD_SLIDE') holdSlide++;
    if (n.lane >= 0 && n.lane < laneCounts.length) laneCounts[n.lane]++;
  }

  $('stat-total').textContent = String(notes.length);
  $('stat-tap').textContent = String(tap);
  $('stat-hold').textContent = String(hold);
  $('stat-slide').textContent = String(slide);
  $('stat-hold-slide').textContent = String(holdSlide);
  const secs = Math.max(1, state.chart.durationMs / 1000);
  $('stat-nps').textContent = (notes.length / secs).toFixed(2);

  const maxLane = Math.max(1, ...laneCounts);
  const barsEl = $('lane-bars');
  barsEl.innerHTML = '';
  laneCounts.forEach((cnt, idx) => {
    const col = document.createElement('div');
    col.className = 'lane-bar-col';
    const pct = Math.max(8, Math.round((cnt / maxLane) * 100));
    col.innerHTML = `<div class="lane-bar-fill" style="height:${pct}%" title="轨道 ${idx + 1}: ${cnt}"></div><span class="lane-bar-num">${cnt}</span>`;
    barsEl.appendChild(col);
  });
}

function rebuildLivePads() {
  const container = $('live-pads');
  const labels = LANE_LABELS[state.chart.laneCount] || LANE_LABELS[4];
  container.innerHTML = '';
  labels.forEach((lbl, idx) => {
    const pad = document.createElement('button');
    pad.type = 'button';
    pad.className = 'live-pad';
    pad.dataset.lane = String(idx);
    pad.textContent = lbl;
    pad.addEventListener('pointerdown', () => handleLiveLaneDown(idx));
    pad.addEventListener('pointerup', () => handleLiveLaneUp(idx));
    container.appendChild(pad);
  });
  $('live-keys-hint').textContent = `实时打谱键位：${labels.join(' / ')}（开启「● 录谱」并播放即可实时按键录谱）`;
  $('preview-keys-desc').textContent = `使用键盘 ${labels.join(' / ')} 实时击打音符 · Esc 关闭`;
}

function setPadVisual(lane, pressed) {
  const pad = document.querySelector(`.live-pad[data-lane="${lane}"]`);
  if (pad) pad.classList.toggle('pressed', pressed);
}

function handleLiveLaneDown(lane) {
  setPadVisual(lane, true);
  if ($('dialog-preview').open) {
    preview3D.pressLane(lane);
    return;
  }
  playSynthClick(880 + lane * 90, 0.03, 0.14);
  if (state.isRecording && !state.activeRecLanes.has(lane)) {
    state.activeRecLanes.set(lane, { startMs: snapTimeMs(getCurrentTimeMs()) });
  }
}

function handleLiveLaneUp(lane) {
  setPadVisual(lane, false);
  if ($('dialog-preview').open) {
    preview3D.releaseLane(lane);
    return;
  }
  if (state.isRecording && state.activeRecLanes.has(lane)) {
    const rec = state.activeRecLanes.get(lane);
    state.activeRecLanes.delete(lane);
    const endMs = snapTimeMs(getCurrentTimeMs());
    const dur = endMs - rec.startMs;
    pushUndo();
    const isHold = dur >= 180;
    state.chart.notes.push({
      id: nextNoteId(),
      timeMs: rec.startMs,
      lane,
      type: isHold ? 'HOLD' : 'TAP',
      durationMs: isHold ? dur : 0,
      slideTargetLane: lane
    });
    state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
    updateStats();
  }
}

// ============================================================================
// Form & Tool Wiring
// ============================================================================
function syncFormFromState() {
  $('input-title').value = state.chart.title;
  $('input-artist').value = state.chart.artist;
  $('input-bpm').value = String(state.chart.bpm);
  $('input-offset').value = String(state.offsetMs);
  $('select-lanes').value = String(state.chart.laneCount);
  $('select-difficulty').value = state.chart.difficulty;
  $('chk-only-tap-hold').checked = Boolean(state.chart.onlyTapAndHold);
  $('time-total').textContent = formatTime(state.chart.durationMs);
}

function setTool(toolName) {
  state.tool = toolName;
  document.querySelectorAll('.tool-btn').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.tool === toolName);
  });
}

// ============================================================================
// Native <dialog> Helper (following modern-web-guidance: dialog + light-dismiss)
// ============================================================================
function setupNativeDialog(dialogEl, onClose) {
  dialogEl.addEventListener('close', () => {
    dialogEl.returnValue = '';
    if (onClose) onClose();
  });

  // Safari fallback for closedby="any"
  if (!('closedBy' in HTMLDialogElement.prototype)) {
    let pointerDownOutside = false;
    dialogEl.addEventListener('pointerdown', (e) => {
      if (e.target !== dialogEl) {
        pointerDownOutside = false;
        return;
      }
      const rect = dialogEl.getBoundingClientRect();
      pointerDownOutside = (
        e.clientX < rect.left || e.clientX > rect.right ||
        e.clientY < rect.top || e.clientY > rect.bottom
      );
    });
    dialogEl.addEventListener('pointerup', (e) => {
      if (!pointerDownOutside || e.target !== dialogEl) return;
      const rect = dialogEl.getBoundingClientRect();
      const outside = (
        e.clientX < rect.left || e.clientX > rect.right ||
        e.clientY < rect.top || e.clientY > rect.bottom
      );
      if (outside) dialogEl.close();
      pointerDownOutside = false;
    });
  }
}

// ============================================================================
// 3D Preview Integration
// ============================================================================
const preview3D = new Preview3DEngine(
  $('preview-canvas'),
  {
    scoreEl: $('hud-score-val'),
    comboEl: $('hud-combo-val'),
    judgeEl: $('hud-judge-val')
  },
  {
    getTimeMs: () => getCurrentTimeMs(),
    onHitSound: (type) => playSynthClick(type === 'SLIDE' ? 1480 : 1080, 0.03, 0.16)
  }
);

function open3DPreview() {
  const dlg = $('dialog-preview');
  preview3D.configure({
    chart: state.chart,
    skinId: $('select-skin').value,
    tiltDeg: Number($('range-tilt').value),
    speedMul: Number($('range-speed').value)
  });
  preview3D.resetStats(getCurrentTimeMs());
  dlg.showModal();
  preview3D.start();
  if (!state.isPlaying) startPlayback();
  $('btn-preview-toggle').textContent = '⏸ 暂停';
}

// ============================================================================
// Export Helpers
// ============================================================================
function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function sanitizeFileName(name) {
  return (name || 'astra_chart').replace(/[\\/:*?"<>|]/g, '_').trim() || 'astra_chart';
}

function exportJsonChart() {
  const blob = new Blob([JSON.stringify(state.chart, null, 2)], { type: 'application/json' });
  const name = `${sanitizeFileName(state.chart.title)}_${state.chart.laneCount}k_${state.chart.difficulty}.json`;
  downloadBlob(blob, name);
  showToast(`已导出 JSON 谱面：${name}`);
}

function exportZipPack() {
  saveCurrentChartSlot();
  const charts = [];
  for (const [key, notes] of Object.entries(state.chartsByKey)) {
    const match = key.match(/^(\d)k_(EASY|NORMAL|HARD|MASTER)$/);
    if (!match || !Array.isArray(notes) || notes.length === 0) continue;
    charts.push({
      ...state.chart,
      laneCount: Number(match[1]),
      difficulty: match[2],
      notes
    });
  }
  const blob = createAstraZipPack({
    chart: state.chart,
    charts: charts.length > 0 ? charts : [state.chart],
    audioBytes: state.audioBytes,
    audioExt: state.audioExt
  });
  const name = `${sanitizeFileName(state.chart.title)}_AstraPack.zip`;
  downloadBlob(blob, name);
  showToast(`已导出 Android 谱面包（含 ${Math.max(1, charts.length)} 个难度）：${name}`);
}

// ============================================================================
// Wire All UI Events
// ============================================================================
function initEventListeners() {
  // Transport
  $('btn-play').addEventListener('click', togglePlayback);
  $('btn-to-start').addEventListener('click', () => seekTo(0));
  $('btn-record').addEventListener('click', () => {
    state.isRecording = !state.isRecording;
    $('btn-record').classList.toggle('active', state.isRecording);
    $('rec-indicator').classList.toggle('recording', state.isRecording);
    showToast(state.isRecording ? '实时录谱已开启：按播放并用键盘打谱' : '实时录谱已关闭');
  });
  $('select-rate').addEventListener('change', (e) => {
    state.playbackRate = Number(e.target.value) || 1.0;
    if (state.isPlaying) {
      const t = getCurrentTimeMs();
      stopPlayback();
      state.currentTimeMs = t;
      startPlayback();
    }
  });

  // Undo / Redo / Import / Export / Help
  $('btn-undo').addEventListener('click', undo);
  $('btn-redo').addEventListener('click', redo);
  $('btn-export-json').addEventListener('click', exportJsonChart);
  $('btn-export-zip').addEventListener('click', exportZipPack);
  $('btn-import').addEventListener('click', () => $('file-import-input').click());
  $('file-import-input').addEventListener('change', (e) => {
    handleFileLoad(e.target.files[0]);
    e.target.value = '';
  });

  // Dropzone
  const dz = $('audio-dropzone');
  dz.addEventListener('click', () => $('file-audio-input').click());
  dz.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      $('file-audio-input').click();
    }
  });
  $('file-audio-input').addEventListener('change', (e) => {
    handleFileLoad(e.target.files[0]);
    e.target.value = '';
  });
  dz.addEventListener('dragover', (e) => { e.preventDefault(); dz.classList.add('dragover'); });
  dz.addEventListener('dragleave', () => dz.classList.remove('dragover'));
  dz.addEventListener('drop', (e) => {
    e.preventDefault();
    dz.classList.remove('dragover');
    if (e.dataTransfer.files[0]) handleFileLoad(e.dataTransfer.files[0]);
  });

  $('btn-load-demo').addEventListener('click', () => loadDemoTrack(true));

  // Metadata inputs
  $('input-title').addEventListener('input', (e) => { state.chart.title = e.target.value; scheduleAutosave(); });
  $('input-artist').addEventListener('input', (e) => { state.chart.artist = e.target.value; scheduleAutosave(); });
  $('input-bpm').addEventListener('change', (e) => {
    state.chart.bpm = Math.max(40, Math.min(320, Number(e.target.value) || 128));
    scheduleAutosave();
  });
  $('input-offset').addEventListener('change', (e) => {
    state.offsetMs = Number(e.target.value) || 0;
    scheduleAutosave();
  });
  $('select-lanes').addEventListener('change', (e) => {
    saveCurrentChartSlot();
    const newLanes = Number(e.target.value);
    pushUndo();
    state.chart.laneCount = newLanes;
    const key = currentChartKey(newLanes, state.chart.difficulty);
    if (Array.isArray(state.chartsByKey[key])) {
      state.chart.notes = state.chartsByKey[key].map((n) => ({
        ...n,
        ...(n.pathPoints ? { pathPoints: n.pathPoints.map((p) => ({ ...p })) } : {})
      }));
    } else if (state.audioBuffer) {
      const { notes } = generateAutoChart(state.audioBuffer, {
        bpm: state.chart.bpm,
        offsetMs: state.offsetMs,
        laneCount: newLanes,
        difficulty: state.chart.difficulty,
        onlyTapAndHold: state.chart.onlyTapAndHold,
        densityScale: Number($('ac-density').value),
        minHoldMs: Number($('ac-hold-min').value),
        drumDoubles: $('ac-drum-doubles').checked,
        quantize: $('ac-quantize').checked
      });
      state.chart.notes = notes;
      state.chartsByKey[key] = notes;
    } else {
      for (const n of state.chart.notes) {
        n.lane = Math.min(newLanes - 1, n.lane);
        n.slideTargetLane = Math.min(newLanes - 1, n.slideTargetLane ?? n.lane);
      }
    }
    state.selectedIds.clear();
    rebuildLivePads();
    updateStats();
    refreshInspector();
    scheduleAutosave();
    showToast(`已切换至 ${newLanes}K · ${state.chart.difficulty}（${state.chart.notes.length} 音符）`);
  });

  $('select-difficulty').addEventListener('change', (e) => {
    saveCurrentChartSlot();
    const newDiff = e.target.value;
    pushUndo();
    state.chart.difficulty = newDiff;
    const key = currentChartKey(state.chart.laneCount, newDiff);
    if (Array.isArray(state.chartsByKey[key])) {
      state.chart.notes = state.chartsByKey[key].map((n) => ({
        ...n,
        ...(n.pathPoints ? { pathPoints: n.pathPoints.map((p) => ({ ...p })) } : {})
      }));
      showToast(`已切换至 ${newDiff} 谱面（${state.chart.notes.length} 音符）`);
    } else if (state.audioBuffer) {
      const { notes } = generateAutoChart(state.audioBuffer, {
        bpm: state.chart.bpm,
        offsetMs: state.offsetMs,
        laneCount: state.chart.laneCount,
        difficulty: newDiff,
        onlyTapAndHold: state.chart.onlyTapAndHold,
        densityScale: Number($('ac-density').value),
        minHoldMs: Number($('ac-hold-min').value),
        drumDoubles: $('ac-drum-doubles').checked,
        quantize: $('ac-quantize').checked
      });
      state.chart.notes = notes;
      state.chartsByKey[key] = notes;
      showToast(`已为 ${newDiff} 难度自动生成 ${notes.length} 个音符`);
    }
    state.selectedIds.clear();
    refreshInspector();
    updateStats();
    scheduleAutosave();
  });
  $('chk-only-tap-hold').addEventListener('change', (e) => {
    state.chart.onlyTapAndHold = e.target.checked;
    scheduleAutosave();
  });

  // Tap BPM
  $('btn-tap-bpm').addEventListener('click', () => {
    const now = performance.now();
    if (state.tapBpmTimes.length > 0 && now - state.tapBpmTimes[state.tapBpmTimes.length - 1] > 2000) {
      state.tapBpmTimes = [];
    }
    state.tapBpmTimes.push(now);
    if (state.tapBpmTimes.length > 8) state.tapBpmTimes.shift();
    if (state.tapBpmTimes.length >= 2) {
      const span = state.tapBpmTimes[state.tapBpmTimes.length - 1] - state.tapBpmTimes[0];
      const avg = span / (state.tapBpmTimes.length - 1);
      const bpm = Math.round((60000 / avg) * 2) / 2;
      if (bpm >= 40 && bpm <= 320) {
        state.chart.bpm = bpm;
        $('input-bpm').value = String(bpm);
        showToast(`测算 BPM：${bpm}`);
      }
    }
  });

  // Grid & Zoom
  $('select-snap').addEventListener('change', (e) => { state.snapDiv = Number(e.target.value); });
  $('range-zoom').addEventListener('input', (e) => {
    state.zoomPxPerSec = Number(e.target.value);
    $('zoom-label').textContent = String(state.zoomPxPerSec);
  });
  $('chk-show-waveform').addEventListener('change', (e) => { state.showWaveform = e.target.checked; });
  scrubber.addEventListener('input', (e) => {
    const ratio = Number(e.target.value) / 1000;
    seekTo(ratio * state.chart.durationMs);
  });

  // Tools
  document.querySelectorAll('.tool-btn').forEach((btn) => {
    btn.addEventListener('click', () => setTool(btn.dataset.tool));
  });

  // Inspector editing
  const applyInspectorChange = () => {
    if (state.selectedIds.size === 0) return;
    pushUndo();
    const type = $('insp-type').value;
    const timeMs = Math.max(0, Math.round(Number($('insp-time').value) || 0));
    const lane = Math.max(0, Math.min(state.chart.laneCount - 1, Number($('insp-lane').value) || 0));
    const durationMs = Math.max(0, Math.round(Number($('insp-duration').value) || 0));
    const targetLane = Math.max(0, Math.min(state.chart.laneCount - 1, Number($('insp-target-lane').value) || 0));
    for (const n of state.chart.notes) {
      if (!state.selectedIds.has(n.id)) continue;
      n.type = type;
      if (state.selectedIds.size === 1) {
        n.timeMs = timeMs;
        n.lane = lane;
      }
      n.durationMs = (type === 'HOLD' || type === 'HOLD_SLIDE') ? Math.max(120, durationMs) : 0;
      n.slideTargetLane = (type === 'SLIDE' || type === 'HOLD_SLIDE') ? targetLane : n.lane;
    }
    state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
    updateStats();
  };
  ['insp-type', 'insp-time', 'insp-lane', 'insp-duration', 'insp-target-lane'].forEach((id) => {
    $(id).addEventListener('change', applyInspectorChange);
  });

  document.querySelectorAll('[data-nudge]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const delta = Number(btn.dataset.nudge);
      if (state.selectedIds.size === 0) return;
      pushUndo();
      for (const n of state.chart.notes) {
        if (!state.selectedIds.has(n.id)) continue;
        n.timeMs = Math.max(0, Math.min(state.chart.durationMs, n.timeMs + delta));
        if (n.pathPoints) n.pathPoints.forEach((p) => { p.timeMs = Math.max(0, p.timeMs + delta); });
      }
      state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
      refreshInspector();
    });
  });

  $('btn-delete-selected').addEventListener('click', deleteSelected);

  // Batch Actions
  $('btn-select-all').addEventListener('click', () => {
    state.selectedIds = new Set(state.chart.notes.map((n) => n.id));
    refreshInspector();
  });
  $('btn-copy').addEventListener('click', copySelected);
  $('btn-paste').addEventListener('click', pasteAtPlayhead);
  $('btn-mirror').addEventListener('click', () => {
    const targets = state.selectedIds.size > 0
      ? state.chart.notes.filter((n) => state.selectedIds.has(n.id))
      : state.chart.notes;
    if (targets.length === 0) return;
    pushUndo();
    const maxL = state.chart.laneCount - 1;
    for (const n of targets) {
      n.lane = maxL - n.lane;
      n.slideTargetLane = maxL - (n.slideTargetLane ?? n.lane);
      if (n.pathPoints) n.pathPoints.forEach((p) => { p.lane = maxL - p.lane; });
    }
    updateStats();
    refreshInspector();
    showToast('已左右镜像翻转轨道');
  });
  $('btn-quantize').addEventListener('click', () => {
    const targets = state.selectedIds.size > 0
      ? state.chart.notes.filter((n) => state.selectedIds.has(n.id))
      : state.chart.notes;
    if (targets.length === 0) return;
    pushUndo();
    for (const n of targets) {
      const snapped = snapTimeMs(n.timeMs);
      const diff = snapped - n.timeMs;
      n.timeMs = snapped;
      if (n.pathPoints) n.pathPoints.forEach((p) => { p.timeMs = Math.max(0, p.timeMs + diff); });
    }
    state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
    refreshInspector();
    showToast('已对齐至当前节拍网格');
  });
  $('btn-clear-all').addEventListener('click', () => {
    if (state.chart.notes.length === 0) return;
    pushUndo();
    state.chart.notes = [];
    state.selectedIds.clear();
    refreshInspector();
    updateStats();
    showToast('已清空全部音符（可按 Ctrl+Z 撤销）');
  });

  // 3D Visual Settings
  $('range-tilt').addEventListener('input', (e) => { $('tilt-label').textContent = e.target.value; });
  $('range-speed').addEventListener('input', (e) => { $('speed-label').textContent = Number(e.target.value).toFixed(1); });

  // Modals
  setupNativeDialog($('dialog-preview'), () => {
    preview3D.stop();
    stopPlayback();
  });
  setupNativeDialog($('dialog-autochart'));
  setupNativeDialog($('dialog-douyin'));
  setupNativeDialog($('dialog-help'));

  document.querySelectorAll('[data-close-dialog]').forEach((btn) => {
    btn.addEventListener('click', () => $(btn.dataset.closeDialog).close());
  });

  $('btn-preview-3d').addEventListener('click', open3DPreview);
  $('btn-preview-toggle').addEventListener('click', () => {
    togglePlayback();
    $('btn-preview-toggle').textContent = state.isPlaying ? '⏸ 暂停' : '▶ 继续';
  });
  $('btn-preview-restart').addEventListener('click', () => {
    const startMs = Math.max(0, getCurrentTimeMs() - 2000);
    seekTo(startMs);
    preview3D.resetStats(startMs);
    if (!state.isPlaying) startPlayback();
  });

  $('btn-help').addEventListener('click', () => $('dialog-help').showModal());

  // Douyin / Link Import Dialog
  const CUSTOM_API_KEY = 'astra_echo_custom_parser_api';
  $('douyin-custom-api').value = localStorage.getItem(CUSTOM_API_KEY) || '';
  $('btn-open-douyin').addEventListener('click', () => {
    $('douyin-status').hidden = true;
    $('dialog-douyin').showModal();
    $('douyin-input').focus();
  });

  $('btn-run-douyin-import').addEventListener('click', async () => {
    const inputText = $('douyin-input').value.trim();
    const customParserApiUrl = $('douyin-custom-api').value.trim();
    const statusEl = $('douyin-status');
    const runBtn = $('btn-run-douyin-import');

    if (!inputText) {
      statusEl.className = 'import-status error';
      statusEl.textContent = '请先粘贴抖音分享口令或网络音视频链接。';
      statusEl.hidden = false;
      return;
    }

    localStorage.setItem(CUSTOM_API_KEY, customParserApiUrl);
    runBtn.disabled = true;
    runBtn.textContent = '解析下载中…';
    statusEl.className = 'import-status';
    statusEl.textContent = '正在解析抖音分享链接并拉取音频流，请稍候…';
    statusEl.hidden = false;

    try {
      const resp = await fetch('/api/import-link', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ inputText, customParserApiUrl })
      });

      if (!resp.ok) {
        let errMsg = `导入失败 (HTTP ${resp.status})`;
        try {
          const errJson = await resp.json();
          if (errJson.error) errMsg = errJson.error;
        } catch (_) {}
        throw new Error(errMsg);
      }

      const title = decodeURIComponent(resp.headers.get('X-Media-Title') || '') || '抖音导入曲目';
      const artist = decodeURIComponent(resp.headers.get('X-Media-Artist') || '') || '抖音链接导入';
      const ext = resp.headers.get('X-Media-Ext') || 'mp4';

      statusEl.textContent = '媒体流下载完成，正在解码音频与波形…';
      const ab = await resp.arrayBuffer();
      const bytes = new Uint8Array(ab);
      await decodeAndSetAudio(bytes, ext, `${title}.${ext}`);

      state.chart.songId = `dy_${Date.now()}`;
      state.chart.title = title;
      state.chart.artist = artist;
      state.chartsByKey = {};

      if ($('douyin-autochart').checked && state.audioBuffer) {
        statusEl.textContent = '音频解码完成，正在分析主音与音高走势并生成全部 4 个难度谱面…';
        pushUndo();
        let detected = state.chart.bpm;
        let cachedAnalysis = null;
        for (const diff of ALL_DIFFS) {
          const res = generateAutoChart(state.audioBuffer, {
            bpm: detected,
            offsetMs: state.offsetMs,
            laneCount: state.chart.laneCount,
            difficulty: diff,
            onlyTapAndHold: state.chart.onlyTapAndHold,
            autoDetectBpm: diff === ALL_DIFFS[0],
            quantize: false,
            _cachedAnalysis: cachedAnalysis
          });
          if (diff === ALL_DIFFS[0]) {
            cachedAnalysis = res.analysis;
            if (res.detectedBpm) {
              detected = res.detectedBpm;
              state.chart.bpm = detected;
            }
            if (typeof res.detectedOffsetMs === 'number') {
              state.offsetMs = res.detectedOffsetMs;
            }
          }
          state.chartsByKey[currentChartKey(state.chart.laneCount, diff)] = res.notes;
        }
        state.chart.notes = state.chartsByKey[currentChartKey()] || [];
        state.selectedIds.clear();
      }

      syncFormFromState();
      refreshInspector();
      updateStats();
      scheduleAutosave();
      $('dialog-douyin').close();
      showToast(`已导入：${title}（BPM ${state.chart.bpm}，可随时切换 4 个难度）`);
    } catch (err) {
      statusEl.className = 'import-status error';
      statusEl.textContent = err?.message || '解析导入失败，请检查链接或网络状态。';
    } finally {
      runBtn.disabled = false;
      runBtn.textContent = '解析并导入';
    }
  });

  // Auto-Charter Dialog
  $('btn-auto-chart').addEventListener('click', () => $('dialog-autochart').showModal());
  $('ac-density').addEventListener('input', (e) => {
    $('ac-density-label').textContent = Number(e.target.value).toFixed(1);
  });
  $('btn-run-autochart').addEventListener('click', () => {
    if (!state.audioBuffer) {
      showToast('请先加载音频或点击「示例曲」');
      return;
    }
    pushUndo();
    const generateAll = $('ac-all-diffs').checked;
    const autoBpm = $('ac-auto-bpm').checked;
    const baseOpts = {
      bpm: state.chart.bpm,
      offsetMs: state.offsetMs,
      laneCount: state.chart.laneCount,
      onlyTapAndHold: state.chart.onlyTapAndHold,
      densityScale: Number($('ac-density').value),
      minHoldMs: Number($('ac-hold-min').value),
      drumDoubles: $('ac-drum-doubles').checked,
      quantize: $('ac-quantize').checked
    };

    if (generateAll) {
      let detected = state.chart.bpm;
      let cachedAnalysis = null;
      const summary = [];
      for (const diff of ALL_DIFFS) {
        const res = generateAutoChart(state.audioBuffer, {
          ...baseOpts,
          bpm: detected,
          difficulty: diff,
          autoDetectBpm: autoBpm && diff === ALL_DIFFS[0],
          _cachedAnalysis: cachedAnalysis
        });
        if (diff === ALL_DIFFS[0]) {
          cachedAnalysis = res.analysis;
          if (autoBpm && res.detectedBpm) {
            detected = res.detectedBpm;
            state.chart.bpm = detected;
          }
          if (autoBpm && typeof res.detectedOffsetMs === 'number') {
            state.offsetMs = res.detectedOffsetMs;
          }
        }
        state.chartsByKey[currentChartKey(state.chart.laneCount, diff)] = res.notes;
        summary.push(`${diff}:${res.notes.length}`);
      }
      state.chart.notes = state.chartsByKey[currentChartKey()] || [];
      syncFormFromState();
      state.selectedIds.clear();
      $('dialog-autochart').close();
      refreshInspector();
      updateStats();
      scheduleAutosave();
      showToast(`4 难度生成完成（BPM ${state.chart.bpm}）· ${summary.join(' / ')}`);
    } else {
      const res = generateAutoChart(state.audioBuffer, {
        ...baseOpts,
        difficulty: state.chart.difficulty,
        autoDetectBpm: autoBpm
      });
      if (autoBpm && res.detectedBpm) {
        state.chart.bpm = res.detectedBpm;
      }
      if (autoBpm && typeof res.detectedOffsetMs === 'number') {
        state.offsetMs = res.detectedOffsetMs;
      }
      state.chart.notes = res.notes;
      state.chartsByKey[currentChartKey()] = res.notes;
      syncFormFromState();
      state.selectedIds.clear();
      $('dialog-autochart').close();
      refreshInspector();
      updateStats();
      scheduleAutosave();
      showToast(`自动制谱完成（${state.chart.difficulty}）：共生成 ${res.notes.length} 个音符`);
    }
  });

  // Global Keyboard Shortcuts
  window.addEventListener('keydown', (e) => {
    const tag = document.activeElement?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

    const previewOpen = $('dialog-preview').open;
    const keys = LANE_KEYS[state.chart.laneCount] || LANE_KEYS[4];
    const laneIdx = keys.indexOf(e.code);

    if (laneIdx !== -1 && (previewOpen || state.isRecording)) {
      if (e.repeat) return;
      e.preventDefault();
      handleLiveLaneDown(laneIdx);
      return;
    }

    if (previewOpen) return;

    if (e.code === 'Space') {
      e.preventDefault();
      togglePlayback();
    } else if (e.code === 'Home') {
      e.preventDefault();
      seekTo(0);
    } else if (e.key === '1') setTool('SELECT');
    else if (e.key === '2') setTool('TAP');
    else if (e.key === '3') setTool('HOLD');
    else if (e.key === '4') setTool('SLIDE');
    else if (e.key === '5') setTool('HOLD_SLIDE');
    else if (e.key === '6') setTool('ERASE');
    else if (e.key.toLowerCase() === 'r' && !e.ctrlKey) $('btn-record').click();
    else if (e.key.toLowerCase() === 'p' && !e.ctrlKey) open3DPreview();
    else if (e.key === '?') $('dialog-help').showModal();
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
      e.preventDefault();
      state.selectedIds = new Set(state.chart.notes.map((n) => n.id));
      refreshInspector();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c') { e.preventDefault(); copySelected(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v') { e.preventDefault(); pasteAtPlayhead(); }
    else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); deleteSelected(); }
  });

  window.addEventListener('keyup', (e) => {
    const keys = LANE_KEYS[state.chart.laneCount] || LANE_KEYS[4];
    const laneIdx = keys.indexOf(e.code);
    if (laneIdx !== -1) {
      handleLiveLaneUp(laneIdx);
    }
  });
}

function deleteSelected() {
  if (state.selectedIds.size === 0) return;
  pushUndo();
  state.chart.notes = state.chart.notes.filter((n) => !state.selectedIds.has(n.id));
  state.selectedIds.clear();
  refreshInspector();
  updateStats();
}

function copySelected() {
  const selected = state.chart.notes.filter((n) => state.selectedIds.has(n.id));
  if (selected.length === 0) return;
  const baseT = Math.min(...selected.map((n) => n.timeMs));
  state.clipboard = selected.map((n) => ({
    ...n,
    relTime: n.timeMs - baseT,
    pathPoints: n.pathPoints ? n.pathPoints.map((p) => ({ relTime: p.timeMs - baseT, lane: p.lane })) : undefined
  }));
  showToast(`已复制 ${selected.length} 个音符`);
}

function pasteAtPlayhead() {
  if (state.clipboard.length === 0) return;
  pushUndo();
  const base = snapTimeMs(getCurrentTimeMs());
  let id = nextNoteId();
  const newIds = new Set();
  for (const item of state.clipboard) {
    const note = {
      id: id++,
      timeMs: Math.min(state.chart.durationMs, base + item.relTime),
      lane: Math.min(state.chart.laneCount - 1, item.lane),
      type: item.type,
      durationMs: item.durationMs,
      slideTargetLane: Math.min(state.chart.laneCount - 1, item.slideTargetLane ?? item.lane)
    };
    if (item.pathPoints) {
      note.pathPoints = item.pathPoints.map((p) => ({
        timeMs: Math.min(state.chart.durationMs, base + p.relTime),
        lane: Math.min(state.chart.laneCount - 1, p.lane)
      }));
    }
    state.chart.notes.push(note);
    newIds.add(note.id);
  }
  state.chart.notes.sort((a, b) => a.timeMs - b.timeMs || a.lane - b.lane);
  state.selectedIds = newIds;
  refreshInspector();
  updateStats();
  showToast(`已粘贴 ${newIds.size} 个音符`);
}

// ============================================================================
// Bootstrap
// ============================================================================
async function boot() {
  initEventListeners();
  rebuildLivePads();

  // Restore autosaved chart if available
  let restored = false;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const saved = JSON.parse(raw);
      if (saved && saved.chart && Array.isArray(saved.chart.notes)) {
        state.chart = saved.chart;
        state.chartsByKey = saved.chartsByKey && typeof saved.chartsByKey === 'object' ? saved.chartsByKey : {};
        state.offsetMs = Number(saved.offsetMs) || 0;
        restored = true;
      }
    }
  } catch (_) {}

  await loadDemoTrack(!restored);
  requestAnimationFrame(renderTimeline);
}

boot();
