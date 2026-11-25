import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 5173;

const MOBILE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.wav': 'audio/wav',
  '.mp3': 'audio/mpeg'
};

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 256 * 1024) {
        reject(new Error('请求体过大'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8');
        resolve(text ? JSON.parse(text) : {});
      } catch {
        reject(new Error('无效的 JSON 请求体'));
      }
    });
    req.on('error', reject);
  });
}

function extractFirstUrl(rawText) {
  const match = String(rawText || '').match(/https?:\/\/[^\s"'<>]+/i);
  if (!match) return null;
  return match[0].replace(/[,.)\]，。！!;；]+$/, '');
}

function isDouyinUrl(url) {
  const lower = url.toLowerCase();
  return lower.includes('douyin.com') || lower.includes('iesdouyin.com');
}

function extractRouterDataJson(html) {
  const marker = 'window._ROUTER_DATA';
  const idx = html.indexOf(marker);
  if (idx < 0) return null;
  const eq = html.indexOf('=', idx);
  if (eq < 0) return null;
  const braceStart = html.indexOf('{', eq);
  if (braceStart < 0) return null;
  const scriptEnd = html.indexOf('</script>', braceStart);
  const raw = (scriptEnd > braceStart ? html.slice(braceStart, scriptEnd) : html.slice(braceStart))
    .trim()
    .replace(/;+$/, '');
  return raw.startsWith('{') && raw.endsWith('}') ? raw : null;
}

function findItemDetailJson(node) {
  if (!node || typeof node !== 'object') return null;
  if (!Array.isArray(node) && node.video && (node.desc !== undefined || node.music !== undefined)) {
    return node;
  }
  const values = Array.isArray(node) ? node : Object.values(node);
  for (const v of values) {
    const found = findItemDetailJson(v);
    if (found) return found;
  }
  return null;
}

function guessExt(url, contentType) {
  const ct = (contentType || '').toLowerCase();
  if (ct.includes('audio/mpeg') || ct.includes('mp3')) return 'mp3';
  if (ct.includes('audio/mp4') || ct.includes('m4a') || ct.includes('aac')) return 'm4a';
  if (ct.includes('audio/wav') || ct.includes('wave')) return 'wav';
  if (ct.includes('audio/ogg')) return 'ogg';
  if (ct.includes('video/mp4')) return 'mp4';
  const clean = (url || '').split('?')[0].toLowerCase();
  const extMatch = clean.match(/\.(mp3|m4a|aac|wav|ogg|flac|mp4|webm)$/);
  return extMatch ? extMatch[1] : 'mp4';
}

function hasVerificationGate(html) {
  const lower = (html || '').toLowerCase();
  return (
    lower.includes('verifycenter') ||
    lower.includes('secsdk') ||
    lower.includes('captcha') ||
    lower.includes('baxia') ||
    lower.includes('滑动拼图') ||
    lower.includes('安全验证')
  );
}

async function resolveViaCustomApi(targetUrl, apiTemplate) {
  const encoded = encodeURIComponent(targetUrl);
  let endpoint;
  if (apiTemplate.includes('{url}')) {
    endpoint = apiTemplate.replaceAll('{url}', encoded);
  } else if (apiTemplate.includes('?')) {
    endpoint = `${apiTemplate}&url=${encoded}`;
  } else {
    endpoint = `${apiTemplate}?url=${encoded}`;
  }
  const resp = await fetch(endpoint, { headers: { 'User-Agent': MOBILE_UA } });
  if (!resp.ok) throw new Error(`自定义解析接口返回 HTTP ${resp.status}`);
  const json = await resp.json();
  const data = json.data && typeof json.data === 'object' ? json.data : json;
  const mediaUrl =
    data.music_url || data.audio_url || data.music || data.audio ||
    data.video_url || data.play_url || data.url;
  if (!mediaUrl || typeof mediaUrl !== 'string') {
    throw new Error('自定义解析接口未返回有效的音视频链接');
  }
  return {
    mediaUrl,
    title: String(data.title || data.desc || '抖音链接曲目').slice(0, 40),
    artist: String(data.author || data.nickname || '链接解析导入').slice(0, 28)
  };
}

async function resolveDouyinShareUrl(shareUrl) {
  // 1. Follow short-link redirect to extract canonical video ID
  const redirResp = await fetch(shareUrl, {
    method: 'GET',
    redirect: 'follow',
    headers: { 'User-Agent': MOBILE_UA }
  });
  const finalUrl = redirResp.url || shareUrl;
  const idMatch =
    finalUrl.match(/(?:video|note|slides)\/(\d{15,22})/) ||
    finalUrl.match(/(\d{18,21})/);

  if (!idMatch) {
    throw new Error('未能从抖音链接中识别作品 ID，请检查分享链接是否完整');
  }
  const videoId = idMatch[1];

  // 2. Fetch mobile share page HTML
  const sharePageUrl = finalUrl.includes('iesdouyin.com/share/video/')
    ? finalUrl
    : `https://www.iesdouyin.com/share/video/${videoId}/`;

  const pageResp = await fetch(sharePageUrl, {
    method: 'GET',
    redirect: 'follow',
    headers: {
      'User-Agent': MOBILE_UA,
      'Accept-Language': 'zh-CN,zh;q=0.9'
    }
  });
  const html = await pageResp.text();

  // Respect platform access controls: stop cleanly if a verification/CAPTCHA gate is returned
  if (hasVerificationGate(html)) {
    const err = new Error(
      '抖音分享页当前触发了平台风控/人机验证，无法直接抓取。请在下方填写自定义解析接口，或先将音频/视频保存到本地后直接拖入编辑器。'
    );
    err.statusCode = 403;
    throw err;
  }

  const routerJsonStr = extractRouterDataJson(html);
  if (!routerJsonStr) {
    throw new Error('未能从抖音分享页解析到媒体数据（该作品可能已设为私密、下架或需登录访问）');
  }

  let routerObj;
  try {
    routerObj = JSON.parse(routerJsonStr);
  } catch {
    throw new Error('抖音分享页数据格式解析失败');
  }

  const item = findItemDetailJson(routerObj);
  if (!item) {
    throw new Error('未在抖音页面数据中找到作品详情（可能作品已失效或为图文类型）');
  }

  const desc = String(item.desc || '').trim();
  const author = String(item.author?.nickname || '').trim();
  const musicTitle = String(item.music?.title || '').trim();
  const musicList = item.music?.play_url?.url_list;
  const musicUrl = Array.isArray(musicList) ? musicList.find((u) => typeof u === 'string' && u.startsWith('http')) : null;

  const videoList = item.video?.play_addr?.url_list;
  const rawVideoUrl = Array.isArray(videoList)
    ? videoList.find((u) => typeof u === 'string' && u.startsWith('http'))?.replace('playwm', 'play')
    : null;

  const mediaUrl = musicUrl || rawVideoUrl;
  if (!mediaUrl) {
    throw new Error('未能提取到可下载的音频或视频直链');
  }

  const cleanDesc = desc.replace(/#\S+/g, '').trim() || desc;
  const title = (cleanDesc || musicTitle || `抖音作品_${videoId}`).slice(0, 38);
  const artist = author ? `抖音 · ${author}` : '抖音链接导入';
  return { mediaUrl, title, artist };
}

async function handleImportLink(req, res) {
  try {
    const body = await readJsonBody(req);
    const rawUrl = extractFirstUrl(body.inputText);
    if (!rawUrl) {
      return sendJson(res, 400, { error: '未在输入内容中检测到有效的 http/https 链接' });
    }

    const customApi = String(body.customParserApiUrl || '').trim();
    let resolved;
    if (customApi && isDouyinUrl(rawUrl)) {
      resolved = await resolveViaCustomApi(rawUrl, customApi);
    } else if (isDouyinUrl(rawUrl)) {
      resolved = await resolveDouyinShareUrl(rawUrl);
    } else {
      const guessedName = rawUrl.split('?')[0].split('/').pop()?.replace(/\.[^.]+$/, '') || '网络音频';
      resolved = {
        mediaUrl: rawUrl,
        title: decodeURIComponent(guessedName).slice(0, 40),
        artist: '网络链接导入'
      };
    }

    // Download the media stream server-side to avoid browser CORS restrictions
    const mediaResp = await fetch(resolved.mediaUrl, {
      method: 'GET',
      redirect: 'follow',
      headers: {
        'User-Agent': MOBILE_UA,
        'Referer': 'https://www.douyin.com/'
      }
    });

    if (!mediaResp.ok) {
      return sendJson(res, 502, { error: `媒体流下载失败（HTTP ${mediaResp.status}）` });
    }

    const contentType = mediaResp.headers.get('content-type') || 'application/octet-stream';
    if (contentType.includes('text/html')) {
      return sendJson(res, 403, { error: '目标链接返回了网页而非音视频流，可能需要登录或触发了防盗链验证' });
    }

    const arrayBuf = await mediaResp.arrayBuffer();
    const buf = Buffer.from(arrayBuf);
    if (buf.length < 1024) {
      return sendJson(res, 502, { error: '下载到的媒体文件过小，可能链接已失效' });
    }

    const ext = guessExt(mediaResp.url || resolved.mediaUrl, contentType);
    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': buf.length,
      'X-Media-Title': encodeURIComponent(resolved.title),
      'X-Media-Artist': encodeURIComponent(resolved.artist),
      'X-Media-Ext': ext
    });
    res.end(buf);
  } catch (err) {
    const code = err?.statusCode || 500;
    sendJson(res, code, { error: err?.message || '链接解析失败' });
  }
}

const server = http.createServer((req, res) => {
  const urlPath = (req.url || '/').split('?')[0];

  if (urlPath === '/api/import-link' && req.method === 'POST') {
    handleImportLink(req, res);
    return;
  }

  const relPath = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
  const fullPath = path.resolve(__dirname, relPath);
  if (!fullPath.startsWith(__dirname)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }
  fs.readFile(fullPath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    const ext = path.extname(fullPath).toLowerCase();
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`ASTRA ECHO Web Editor running at http://127.0.0.1:${PORT}`);
});
