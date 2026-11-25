/**
 * 3D Perspective Test-Play Preview Engine for 《星律回响 · ASTRA ECHO》
 * Matches the Android SurfaceView perspective trapezoid, 5 NoteSkins,
 * 4K/5K/6K lanes, and live keyboard hit judgment.
 */

export const SKINS = {
  ASTRA_CYBER: {
    name: '星律冰晶',
    bgTop: '#060a18',
    bgBottom: '#101b3a',
    rail: 'rgba(56, 242, 255, 0.24)',
    judgeLine: '#38f2ff',
    tap: '#38f2ff',
    hold: '#64a8ff',
    slide: '#ff3b8d',
    holdSlide: '#ffd166',
    monochrome: false
  },
  CLASSIC_MASTER: {
    name: '经典律动',
    bgTop: '#090d16',
    bgBottom: '#182233',
    rail: 'rgba(255, 255, 255, 0.22)',
    judgeLine: '#ffd166',
    tap: '#4cc9f0',
    hold: '#4895ef',
    slide: '#f72585',
    holdSlide: '#ffd166',
    monochrome: false
  },
  SAKURA_PULSE: {
    name: '樱落幻梦',
    bgTop: '#160a18',
    bgBottom: '#2d1230',
    rail: 'rgba(255, 175, 204, 0.28)',
    judgeLine: '#ffafcc',
    tap: '#ff8fab',
    hold: '#cdb4db',
    slide: '#ff4d6d',
    holdSlide: '#ffe5ec',
    monochrome: false
  },
  CRIMSON_OVERDRIVE: {
    name: '绯红超载',
    bgTop: '#160609',
    bgBottom: '#2e0b14',
    rail: 'rgba(255, 77, 109, 0.28)',
    judgeLine: '#ff4d6d',
    tap: '#ff595e',
    hold: '#ff924c',
    slide: '#ffca3a',
    holdSlide: '#ffffff',
    monochrome: false
  },
  EULER_CHALK: {
    name: '欧拉手稿',
    bgTop: '#111417',
    bgBottom: '#1c2227',
    rail: 'rgba(235, 240, 238, 0.24)',
    judgeLine: '#f2f5f3',
    tap: '#f4f7f5',
    hold: '#cfd8d3',
    slide: '#ffffff',
    holdSlide: '#dce3df',
    monochrome: true
  }
};

export class Preview3DEngine {
  constructor(canvas, hudElements, options) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.hud = hudElements;
    this.getTimeMs = options.getTimeMs;
    this.onHitSound = options.onHitSound;

    this.chart = null;
    this.skinId = 'ASTRA_CYBER';
    this.tiltDeg = 36;
    this.speedMul = 1.0;

    this.running = false;
    this.rafId = null;
    this.hitNotes = new Set();
    this.pressedLanes = new Set();
    this.laneFlash = [];
    this.score = 0;
    this.combo = 0;
    this.judgeText = '';
    this.judgeColor = '#38f2ff';
    this.judgeUntil = 0;
  }

  configure({ chart, skinId, tiltDeg, speedMul }) {
    this.chart = chart;
    this.skinId = skinId || 'ASTRA_CYBER';
    this.tiltDeg = Number(tiltDeg ?? 36);
    this.speedMul = Number(speedMul ?? 1.0);
    this.laneFlash = new Array(chart.laneCount).fill(0);
  }

  resetStats(startMs = 0) {
    this.hitNotes.clear();
    if (this.chart) {
      for (const n of this.chart.notes) {
        if (n.timeMs < startMs - 140) {
          this.hitNotes.add(n.id);
        }
      }
    }
    this.score = 0;
    this.combo = 0;
    this.judgeText = '';
    this.updateHud();
  }

  start() {
    this.running = true;
    const loop = () => {
      if (!this.running) return;
      this.render();
      this.rafId = requestAnimationFrame(loop);
    };
    this.rafId = requestAnimationFrame(loop);
  }

  stop() {
    this.running = false;
    if (this.rafId) cancelAnimationFrame(this.rafId);
    this.rafId = null;
  }

  pressLane(lane) {
    if (!this.chart || lane < 0 || lane >= this.chart.laneCount) return;
    this.pressedLanes.add(lane);
    this.laneFlash[lane] = 1.0;

    const nowMs = this.getTimeMs();
    // Find nearest unhit note on this lane within ±150ms
    let best = null;
    let bestDiff = 160;
    for (const n of this.chart.notes) {
      if (this.hitNotes.has(n.id)) continue;
      if (n.lane !== lane) continue;
      const diff = Math.abs(n.timeMs - nowMs);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = n;
      }
    }

    if (best) {
      this.hitNotes.add(best.id);
      if (this.onHitSound) this.onHitSound(best.type);
      if (bestDiff <= 55) {
        this.score += 1000;
        this.combo += 1;
        this.showJudge('PERFECT', '#38f2ff');
      } else if (bestDiff <= 105) {
        this.score += 700;
        this.combo += 1;
        this.showJudge('GREAT', '#ffd166');
      } else {
        this.score += 400;
        this.combo += 1;
        this.showJudge('GOOD', '#3be8b0');
      }
      this.updateHud();
    }
  }

  releaseLane(lane) {
    this.pressedLanes.delete(lane);
  }

  showJudge(text, color) {
    this.judgeText = text;
    this.judgeColor = color;
    this.judgeUntil = performance.now() + 420;
  }

  updateHud() {
    if (!this.hud) return;
    this.hud.scoreEl.textContent = String(this.score).padStart(6, '0');
    this.hud.comboEl.textContent = String(this.combo);
    if (performance.now() < this.judgeUntil && this.judgeText) {
      this.hud.judgeEl.textContent = this.judgeText;
      this.hud.judgeEl.style.color = this.judgeColor;
    } else {
      this.hud.judgeEl.textContent = '';
    }
  }

  project(progress, laneFloat, w, h, laneCount) {
    // progress: 0 at horizon (top), 1 at judge line (bottom)
    const tiltFactor = this.tiltDeg / 60; // 0..1
    const topY = h * (0.14 + 0.08 * tiltFactor);
    const judgeY = h * 0.86;
    const bottomWidth = Math.min(w * 0.72, 540);
    const topWidth = bottomWidth * (1 - 0.62 * tiltFactor);

    // Perspective curve
    const z = tiltFactor > 0.05 ? Math.pow(Math.max(0, progress), 1 + tiltFactor * 0.75) : progress;
    const y = topY + (judgeY - topY) * z;
    const rowWidth = topWidth + (bottomWidth - topWidth) * z;
    const leftX = (w - rowWidth) / 2;
    const x = leftX + (laneFloat / laneCount) * rowWidth;
    const laneW = rowWidth / laneCount;
    return { x, y, laneW, scale: 0.35 + 0.65 * z };
  }

  render() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    if (this.canvas.width !== Math.floor(rect.width * dpr) || this.canvas.height !== Math.floor(rect.height * dpr)) {
      this.canvas.width = Math.max(1, Math.floor(rect.width * dpr));
      this.canvas.height = Math.max(1, Math.floor(rect.height * dpr));
    }

    const ctx = this.ctx;
    ctx.save();
    ctx.scale(dpr, dpr);
    const w = rect.width;
    const h = rect.height;
    const skin = SKINS[this.skinId] || SKINS.ASTRA_CYBER;
    const laneCount = this.chart ? this.chart.laneCount : 4;
    const nowMs = this.getTimeMs();

    // Background gradient
    const bgGrad = ctx.createLinearGradient(0, 0, 0, h);
    bgGrad.addColorStop(0, skin.bgTop);
    bgGrad.addColorStop(1, skin.bgBottom);
    ctx.fillStyle = bgGrad;
    ctx.fillRect(0, 0, w, h);

    // Trapezoid highway
    const tl = this.project(0, 0, w, h, laneCount);
    const tr = this.project(0, laneCount, w, h, laneCount);
    const bl = this.project(1.06, 0, w, h, laneCount);
    const br = this.project(1.06, laneCount, w, h, laneCount);

    ctx.beginPath();
    ctx.moveTo(tl.x, tl.y);
    ctx.lineTo(tr.x, tr.y);
    ctx.lineTo(br.x, br.y);
    ctx.lineTo(bl.x, bl.y);
    ctx.closePath();
    ctx.fillStyle = 'rgba(5, 9, 22, 0.72)';
    ctx.fill();

    // Lane highlights when pressed
    for (let l = 0; l < laneCount; l++) {
      if (this.laneFlash[l] > 0.01) {
        const p0 = this.project(0, l, w, h, laneCount);
        const p1 = this.project(0, l + 1, w, h, laneCount);
        const b0 = this.project(1, l, w, h, laneCount);
        const b1 = this.project(1, l + 1, w, h, laneCount);
        ctx.beginPath();
        ctx.moveTo(p0.x, p0.y);
        ctx.lineTo(p1.x, p1.y);
        ctx.lineTo(b1.x, b1.y);
        ctx.lineTo(b0.x, b0.y);
        ctx.closePath();
        ctx.fillStyle = `rgba(56, 242, 255, ${0.22 * this.laneFlash[l]})`;
        ctx.fill();
        if (!this.pressedLanes.has(l)) {
          this.laneFlash[l] *= 0.85;
        }
      }
    }

    // Lane dividers
    ctx.strokeStyle = skin.rail;
    ctx.lineWidth = 1.2;
    for (let l = 0; l <= laneCount; l++) {
      const top = this.project(0, l, w, h, laneCount);
      const bot = this.project(1.06, l, w, h, laneCount);
      ctx.beginPath();
      ctx.moveTo(top.x, top.y);
      ctx.lineTo(bot.x, bot.y);
      ctx.stroke();
    }

    // Judge line
    const jl = this.project(1, 0, w, h, laneCount);
    const jr = this.project(1, laneCount, w, h, laneCount);
    ctx.save();
    ctx.strokeStyle = skin.judgeLine;
    ctx.lineWidth = 3;
    ctx.shadowColor = skin.judgeLine;
    ctx.shadowBlur = 14;
    ctx.beginPath();
    ctx.moveTo(jl.x, jl.y);
    ctx.lineTo(jr.x, jr.y);
    ctx.stroke();
    ctx.restore();

    if (this.chart) {
      const approachMs = 1300 / Math.max(0.4, this.speedMul);

      // Auto-miss check
      for (const n of this.chart.notes) {
        if (!this.hitNotes.has(n.id) && nowMs - n.timeMs > 165) {
          this.hitNotes.add(n.id);
          this.combo = 0;
          this.showJudge('MISS', '#ff4d6d');
        }
      }

      // Render visible notes
      for (const n of this.chart.notes) {
        const endMs = n.timeMs + (n.durationMs || 0);
        if (n.timeMs > nowMs + approachMs || endMs < nowMs - 160) continue;
        if (n.type === 'TAP' && this.hitNotes.has(n.id)) continue;

        const headProg = 1 - (n.timeMs - nowMs) / approachMs;

        // Hold / Hold-Slide body
        if ((n.type === 'HOLD' || n.type === 'HOLD_SLIDE') && n.durationMs > 0) {
          const pts = (n.type === 'HOLD_SLIDE' && Array.isArray(n.pathPoints) && n.pathPoints.length >= 2)
            ? n.pathPoints
            : [{ timeMs: n.timeMs, lane: n.lane }, { timeMs: endMs, lane: n.slideTargetLane ?? n.lane }];

          ctx.fillStyle = n.type === 'HOLD_SLIDE' ? skin.holdSlide : skin.hold;
          ctx.globalAlpha = 0.55;
          for (let i = 0; i < pts.length - 1; i++) {
            const pA = Math.max(0, Math.min(1.05, 1 - (pts[i].timeMs - nowMs) / approachMs));
            const pB = Math.max(0, Math.min(1.05, 1 - (pts[i + 1].timeMs - nowMs) / approachMs));
            if (pA <= 0 && pB <= 0) continue;
            const aL = this.project(pA, pts[i].lane + 0.14, w, h, laneCount);
            const aR = this.project(pA, pts[i].lane + 0.86, w, h, laneCount);
            const bL = this.project(pB, pts[i + 1].lane + 0.14, w, h, laneCount);
            const bR = this.project(pB, pts[i + 1].lane + 0.86, w, h, laneCount);
            ctx.beginPath();
            ctx.moveTo(aL.x, aL.y);
            ctx.lineTo(aR.x, aR.y);
            ctx.lineTo(bR.x, bR.y);
            ctx.lineTo(bL.x, bL.y);
            ctx.closePath();
            ctx.fill();
          }
          ctx.globalAlpha = 1;
        }

        if (headProg >= 0 && headProg <= 1.08) {
          const pos = this.project(headProg, n.lane + 0.5, w, h, laneCount);
          const noteW = pos.laneW * 0.82;
          const noteH = Math.max(6, 16 * pos.scale);
          let color = skin.tap;
          if (n.type === 'HOLD') color = skin.hold;
          if (n.type === 'SLIDE') color = skin.slide;
          if (n.type === 'HOLD_SLIDE') color = skin.holdSlide;

          ctx.fillStyle = color;
          ctx.beginPath();
          ctx.roundRect(pos.x - noteW / 2, pos.y - noteH / 2, noteW, noteH, 5 * pos.scale);
          ctx.fill();

          if (n.type === 'SLIDE') {
            const dir = (n.slideTargetLane ?? n.lane) >= n.lane ? '→' : '←';
            ctx.fillStyle = '#070a16';
            ctx.font = `bold ${Math.max(10, Math.round(13 * pos.scale))}px sans-serif`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(dir, pos.x, pos.y);
          }
        }
      }
    }

    this.updateHud();
    ctx.restore();
  }
}
