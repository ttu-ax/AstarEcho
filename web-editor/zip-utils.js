/**
 * Pure-JS ZIP writer (STORE mode) and reader (STORE + DEFLATE via DecompressionStream)
 * 100% compatible with Android 《星律回响 · ASTRA ECHO》 SongRepository.kt ZIP pack format.
 */

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder('utf-8');

// Precompute CRC-32 lookup table
const CRC_TABLE = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  CRC_TABLE[i] = c >>> 0;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/**
 * Build an uncompressed (STORE) ZIP Blob from [{ name: string, data: Uint8Array }]
 */
export function buildZipBlob(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = textEncoder.encode(entry.name);
    const data = entry.data instanceof Uint8Array ? entry.data : new Uint8Array(entry.data);
    const crc = crc32(data);
    const size = data.length;

    // Local File Header (30 bytes + name)
    const localHeader = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(localHeader.buffer);
    lv.setUint32(0, 0x04034b50, true); // signature
    lv.setUint16(4, 20, true);         // version needed
    lv.setUint16(6, 0x0800, true);     // UTF-8 flag
    lv.setUint16(8, 0, true);          // method 0 = STORE
    lv.setUint16(10, 0, true);         // mod time
    lv.setUint16(12, 0x5421, true);    // mod date
    lv.setUint32(14, crc, true);
    lv.setUint32(18, size, true);      // compressed size
    lv.setUint32(22, size, true);      // uncompressed size
    lv.setUint16(26, nameBytes.length, true);
    lv.setUint16(28, 0, true);         // extra len
    localHeader.set(nameBytes, 30);

    localParts.push(localHeader, data);

    // Central Directory Header (46 bytes + name)
    const centralHeader = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(centralHeader.buffer);
    cv.setUint32(0, 0x02014b50, true); // signature
    cv.setUint16(4, 20, true);         // version made by
    cv.setUint16(6, 20, true);         // version needed
    cv.setUint16(8, 0x0800, true);     // UTF-8 flag
    cv.setUint16(10, 0, true);         // method 0 = STORE
    cv.setUint16(12, 0, true);
    cv.setUint16(14, 0x5421, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, size, true);
    cv.setUint32(24, size, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint16(30, 0, true);         // extra len
    cv.setUint16(32, 0, true);         // comment len
    cv.setUint16(34, 0, true);         // disk start
    cv.setUint16(36, 0, true);         // internal attr
    cv.setUint32(38, 0, true);         // external attr
    cv.setUint32(42, offset, true);    // local header offset
    centralHeader.set(nameBytes, 46);

    centralParts.push(centralHeader);
    offset += localHeader.length + size;
  }

  let centralSize = 0;
  for (const part of centralParts) centralSize += part.length;

  // End of Central Directory Record (22 bytes)
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(4, 0, true);
  ev.setUint16(6, 0, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);
  ev.setUint16(20, 0, true);

  return new Blob([...localParts, ...centralParts, eocd], { type: 'application/zip' });
}

async function inflateRaw(compressedBytes) {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('当前浏览器不支持 DecompressionStream，无法解压 DEFLATE 压缩的 ZIP 文件');
  }
  const ds = new DecompressionStream('deflate-raw');
  const writer = ds.writable.getWriter();
  writer.write(compressedBytes);
  writer.close();
  const ab = await new Response(ds.readable).arrayBuffer();
  return new Uint8Array(ab);
}

/**
 * Parse a ZIP ArrayBuffer into Map<string, Uint8Array> (supports STORE & DEFLATE).
 */
export async function parseZipBuffer(arrayBuffer) {
  const bytes = new Uint8Array(arrayBuffer);
  const view = new DataView(arrayBuffer);

  // Locate End of Central Directory (EOCD)
  let eocdOffset = -1;
  const minOffset = Math.max(0, bytes.length - 65557);
  for (let i = bytes.length - 22; i >= minOffset; i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }
  if (eocdOffset === -1) {
    throw new Error('无效的 ZIP 文件（未找到中央目录）');
  }

  const totalEntries = view.getUint16(eocdOffset + 10, true);
  let cdOffset = view.getUint32(eocdOffset + 16, true);
  const files = new Map();

  for (let i = 0; i < totalEntries; i++) {
    if (view.getUint32(cdOffset, true) !== 0x02014b50) break;
    const method = view.getUint16(cdOffset + 10, true);
    const compSize = view.getUint32(cdOffset + 20, true);
    const nameLen = view.getUint16(cdOffset + 28, true);
    const extraLen = view.getUint16(cdOffset + 30, true);
    const commentLen = view.getUint16(cdOffset + 32, true);
    const localHeaderOffset = view.getUint32(cdOffset + 42, true);

    const name = textDecoder.decode(bytes.subarray(cdOffset + 46, cdOffset + 46 + nameLen));
    cdOffset += 46 + nameLen + extraLen + commentLen;

    if (name.endsWith('/')) continue;

    // Read local header to find exact data start
    const localNameLen = view.getUint16(localHeaderOffset + 26, true);
    const localExtraLen = view.getUint16(localHeaderOffset + 28, true);
    const dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen;
    const rawSlice = bytes.subarray(dataStart, dataStart + compSize);

    if (method === 0) {
      files.set(name, new Uint8Array(rawSlice));
    } else if (method === 8) {
      files.set(name, await inflateRaw(rawSlice));
    } else {
      throw new Error(`不支持的 ZIP 压缩方式: ${method}`);
    }
  }

  return files;
}

/**
 * Create an Android-compatible 《星律回响》 .zip Song+Chart Pack.
 * Supports exporting a single `chart` or an array of `charts` (e.g. EASY/NORMAL/HARD/MASTER).
 */
export function createAstraZipPack({ chart, charts = null, audioBytes, audioExt = 'mp3' }) {
  const chartList = Array.isArray(charts) && charts.length > 0 ? charts : [chart];
  const primary = chartList[0];
  const safeId = (primary.songId || `web_${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, '_');
  const ext = (audioExt || 'mp3').replace(/^\./, '').toLowerCase() || 'mp3';
  const audioEntry = `songs/${safeId}/audio.${ext}`;

  const manifestCharts = [];
  const entries = [];

  for (const c of chartList) {
    const modeSuffix = c.onlyTapAndHold ? 'noSlide' : 'all';
    const chartEntry = `songs/${safeId}/charts/${safeId}_v7_${c.laneCount}k_${c.difficulty}_${modeSuffix}.json`;
    manifestCharts.push({
      laneCount: c.laneCount,
      difficulty: c.difficulty,
      onlyTapAndHold: Boolean(c.onlyTapAndHold),
      entry: chartEntry
    });
    entries.push({ name: chartEntry, data: textEncoder.encode(JSON.stringify(c, null, 2)) });
  }

  const manifest = {
    format: 'astra_echo_pack',
    version: 1,
    songs: [
      {
        origId: safeId,
        title: primary.title || '未命名曲目',
        subtitle: '网页编辑器导出 · 含谱面',
        artist: primary.artist || 'ASTRA STUDIO',
        bpm: Math.round(primary.bpm || 128),
        durationMs: Math.round(primary.durationMs || 60000),
        isFromVideo: false,
        accentHex: 4281922303, // 0xFF38F2FF
        audioEntry: audioBytes ? audioEntry : '',
        audioExt: ext,
        charts: manifestCharts
      }
    ]
  };

  entries.unshift({ name: 'manifest.json', data: textEncoder.encode(JSON.stringify(manifest, null, 2)) });

  if (audioBytes && audioBytes.length > 0) {
    entries.push({ name: audioEntry, data: audioBytes });
  }

  return buildZipBlob(entries);
}

/**
 * Parse an Android or Web-exported 《星律回响》 .zip Song+Chart Pack.
 * Returns { manifest, chart, audioBytes, audioExt }
 */
export async function parseAstraZipPack(arrayBuffer) {
  const files = await parseZipBuffer(arrayBuffer);
  const manifestBytes = files.get('manifest.json');
  if (!manifestBytes) {
    // Fallback: maybe it's a zip with just a json + audio
    let chart = null;
    let audioBytes = null;
    let audioExt = 'mp3';
    for (const [name, data] of files.entries()) {
      if (name.endsWith('.json') && !chart) {
        chart = JSON.parse(textDecoder.decode(data));
      } else if (/\.(mp3|wav|ogg|m4a|aac|flac)$/i.test(name) && !audioBytes) {
        audioBytes = data;
        audioExt = name.split('.').pop().toLowerCase();
      }
    }
    if (!chart && !audioBytes) {
      throw new Error('ZIP 包内未找到 manifest.json 或有效的谱面/音频文件');
    }
    return { manifest: null, chart, audioBytes, audioExt };
  }

  const manifest = JSON.parse(textDecoder.decode(manifestBytes));
  const song = manifest.songs && manifest.songs[0];
  if (!song) {
    throw new Error('manifest.json 中没有歌曲记录');
  }

  let chart = null;
  if (song.charts && song.charts.length > 0) {
    const firstChartEntry = song.charts[0].entry;
    const chartBytes = files.get(firstChartEntry);
    if (chartBytes) {
      chart = JSON.parse(textDecoder.decode(chartBytes));
    }
  }

  let audioBytes = null;
  let audioExt = song.audioExt || 'mp3';
  if (song.audioEntry && files.has(song.audioEntry)) {
    audioBytes = files.get(song.audioEntry);
  }

  return { manifest, chart, audioBytes, audioExt };
}
