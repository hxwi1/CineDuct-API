// 逐帧渲染：起一个本地静态服务，用 Chrome headless + CDP 截图。
// 零依赖，靠 Node 24 自带的 WebSocket。
//
// 用法：
//   node capture/render.mjs --fps 30 --from 0 --to 11600
//   可选：--dir out/frames --prefix f --width 1920 --height 1080
//        --chrome "C:\Program Files\Google\Chrome\Application\chrome.exe"
//        --step 1（抽帧调试用）
//
// 帧号按绝对时间算：f_00120 永远是第 4 秒那一帧，分段渲染不会互相覆盖。

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src');

const args = Object.fromEntries(
  process.argv.slice(2).map(a => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/);
    return m ? [m[1], m[2] ?? 'true'] : [a, 'true'];
  })
);

const FPS = Number(args.fps ?? 30);
const FROM = Number(args.from ?? 0);
const TO = Number(args.to ?? 0) || 12000;
const STEP = Number(args.step ?? 1);
const WIDTH = Number(args.width ?? 1920);
const HEIGHT = Number(args.height ?? 1080);
const OUTDIR = path.resolve(ROOT, args.dir ?? 'out/frames');
const PREFIX = args.prefix ?? 'f';
const PORT = Number(args.port ?? 9333);
const CHROME = args.chrome ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

const sleep = ms => new Promise(r => setTimeout(r, ms));

function startServer() {
  return new Promise(resolve => {
    const server = http.createServer((req, res) => {
      const urlPath = decodeURIComponent(req.url.split('?')[0]);
      const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
      const file = path.join(SRC, rel);
      if (!file.startsWith(SRC) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
        res.writeHead(404).end('not found');
        return;
      }
      res.writeHead(200, {
        'content-type': MIME[path.extname(file)] ?? 'application/octet-stream',
        'cache-control': 'no-store, must-revalidate',
      });
      fs.createReadStream(file).pipe(res);
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

class CDP {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.id = 0;
    this.pending = new Map();
    this.events = [];
  }
  async open() {
    await new Promise((res, rej) => {
      this.ws.addEventListener('open', res, { once: true });
      this.ws.addEventListener('error', () => rej(new Error('CDP socket error')), { once: true });
    });
    this.ws.addEventListener('message', ev => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { res, rej } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? rej(new Error(`${msg.error.message} (${msg.error.code})`)) : res(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    this.ws.send(JSON.stringify(payload));
    return new Promise((res, rej) => {
      this.pending.set(id, { res, rej });
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          rej(new Error(`CDP timeout: ${method}`));
        }
      }, 30000);
    });
  }
}

async function waitFor(fn, timeoutMs, label) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const v = await fn();
      if (v) return v;
    } catch {}
    await sleep(120);
  }
  throw new Error(`等待超时：${label}`);
}

async function main() {
  if (!fs.existsSync(CHROME)) throw new Error(`找不到 Chrome：${CHROME}`);
  fs.mkdirSync(OUTDIR, { recursive: true });

  const server = await startServer();
  const { port } = server.address();
  const pageUrl = `http://127.0.0.1:${port}/index.html`;

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cineduct-promo-'));
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`,
    `--window-size=${WIDTH},${HEIGHT}`,
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    '--disable-lcd-text',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding',
    '--disable-extensions',
    'about:blank',
  ], { stdio: ['ignore', 'pipe', 'pipe'] });

  chrome.stderr.on('data', () => {});

  const cleanup = async () => {
    try { server.close(); } catch {}
    try { chrome.kill(); } catch {}
    await sleep(600);
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch {}
  };

  try {
    const version = await waitFor(async () => {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      return r.ok ? r.json() : null;
    }, 20000, 'Chrome DevTools 端口');

    const cdp = new CDP(version.webSocketDebuggerUrl);
    await cdp.open();

    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });

    await cdp.send('Page.enable', {}, sessionId);
    await cdp.send('Runtime.enable', {}, sessionId);
    await cdp.send('Network.enable', {}, sessionId);
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true }, sessionId);
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false,
      screenWidth: WIDTH, screenHeight: HEIGHT,
    }, sessionId);

    await cdp.send('Page.navigate', { url: pageUrl }, sessionId);

    await waitFor(async () => {
      const r = await cdp.send('Runtime.evaluate', { expression: 'window.__ready === true', returnByValue: true }, sessionId);
      return r.result?.value === true;
    }, 20000, '页面加载');

    const meta = await cdp.send('Runtime.evaluate', {
      expression: 'JSON.stringify({ d: window.__duration(), s: window.__scenes() })',
      returnByValue: true,
    }, sessionId);
    console.log('时间轴：', meta.result.value);

    const frames = [];
    const t0 = Date.now();

    for (let ms = FROM; ms < TO; ms += (1000 / FPS) * STEP) {
      const n = Math.round(ms * FPS / 1000);
      await cdp.send('Runtime.evaluate', {
        expression: `window.__seek(${ms})`,
        returnByValue: true,
      }, sessionId);

      await cdp.send('Runtime.evaluate', {
        expression: 'new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(1))))',
        awaitPromise: true,
        returnByValue: true,
      }, sessionId);

      const shot = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        fromSurface: true,
        captureBeyondViewport: false,
      }, sessionId);

      const name = `${PREFIX}_${String(n).padStart(5, '0')}.png`;
      fs.writeFileSync(path.join(OUTDIR, name), Buffer.from(shot.data, 'base64'));
      frames.push({ n, ms, name });

      if (frames.length % 15 === 0 || ms + 1000 / FPS >= TO) {
        const el = ((Date.now() - t0) / 1000).toFixed(1);
        const fps = (frames.length / (Date.now() - t0) * 1000).toFixed(1);
        process.stdout.write(`\r${frames.length} 帧 / ${el}s (${fps} fps 抓取)   `);
      }
    }

    process.stdout.write('\n');

    fs.writeFileSync(path.join(OUTDIR, 'manifest.json'), JSON.stringify({
      fps: FPS, step: STEP, width: WIDTH, height: HEIGHT,
      from: FROM, to: TO, count: frames.length, frames,
    }, null, 2));

    console.log(`完成：${frames.length} 帧 → ${OUTDIR}`);
    await cdp.send('Browser.close');
    await sleep(300);
  } finally {
    await cleanup();
  }
}

main().catch(err => {
  console.error('\n渲染失败：', err.message);
  process.exit(1);
});
