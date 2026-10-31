// server.js — HTTP(S) (puerto 3000) sirve public/ y, en el mismo puerto, un WebSocket que hace "beep" al recibir "BEEP"
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFile, writeFile } from 'node:fs/promises';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { WebSocketServer } from 'ws';
import wavPlayer from 'node-wav-player';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, 'public');
const HOST = '0.0.0.0';
const HTTP_PORT = Number(process.env.HTTP_PORT) || 3000;
// Con TLS_CERT y TLS_KEY (rutas a PEM) sirve HTTPS/WSS directo; sin ellas, HTTP (para usar detrás de un proxy con TLS)
const TLS = process.env.TLS_CERT && process.env.TLS_KEY
  ? { cert: await readFile(process.env.TLS_CERT), key: await readFile(process.env.TLS_KEY) }
  : null;

// Clave para POST /beep (app Android): BEEP_KEY o beep-key.txt; si no hay ninguna, se genera y se guarda
const KEY_PATH = path.join(__dirname, 'beep-key.txt');
let BEEP_KEY = process.env.BEEP_KEY || (await readFile(KEY_PATH, 'utf8').catch(() => '')).trim();
if (!BEEP_KEY) {
  BEEP_KEY = randomBytes(18).toString('base64url');
  await writeFile(KEY_PATH, BEEP_KEY + '\n');
}
const validKey = (header = '') => {
  const given = Buffer.from(header.replace(/^Bearer\s+/i, ''));
  const want = Buffer.from(BEEP_KEY);
  return given.length === want.length && timingSafeEqual(given, want);
};
const HEARTBEAT_MS = 15_000;

// ---------------------------------------------------------------------------
// Utilidades de log
// ---------------------------------------------------------------------------
const ts = () => {
  const d = new Date();
  return d.toLocaleTimeString('es-AR', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
};
const cleanIp = (ip = '') => ip.replace(/^::ffff:/, '');
const log = (msg) => console.log(`[${ts()}] ${msg}`);

// ---------------------------------------------------------------------------
// Beep: generamos un WAV de tono puro (880 Hz, 150 ms) una sola vez y lo
// reproducimos con node-wav-player (afplay en macOS, aplay en Linux,
// PowerShell en Windows). Si falla, caemos al "bell" de la terminal.
// ---------------------------------------------------------------------------
function makeBeepWav({ freq = 880, ms = 150, rate = 44100, volume = 0.6 } = {}) {
  const samples = Math.floor((rate * ms) / 1000);
  const data = Buffer.alloc(samples * 2);
  const fade = Math.floor(rate * 0.005); // 5 ms de fade para evitar "clicks"
  for (let i = 0; i < samples; i++) {
    let amp = volume;
    if (i < fade) amp *= i / fade;
    if (i > samples - fade) amp *= (samples - i) / fade;
    data.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * amp * 32767), i * 2);
  }
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);      // tamaño del bloque fmt
  header.writeUInt16LE(1, 20);       // PCM
  header.writeUInt16LE(1, 22);       // mono
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * 2, 28); // byte rate
  header.writeUInt16LE(2, 32);       // block align
  header.writeUInt16LE(16, 34);      // bits por muestra
  header.write('data', 36);
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

const BEEP_PATH = path.join(os.tmpdir(), 'selfie-beep-880hz.wav');
await writeFile(BEEP_PATH, makeBeepWav());

let audioWarned = false;
async function beep() {
  try {
    await wavPlayer.play({ path: BEEP_PATH });
  } catch (err) {
    process.stdout.write('\x07'); // fallback: campana de la terminal
    if (!audioWarned) {
      audioWarned = true;
      log(`⚠️  No se pudo reproducir el WAV (${err.message}). Usando la campana de la terminal.`);
    }
  }
}

// ---------------------------------------------------------------------------
// Servidor HTTP estático (public/)
// ---------------------------------------------------------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive',
};

// Vibraciones que la PC le manda al celular: [pausa, vibración, pausa, vibración, …] en ms
const VIBRATIONS = {
  a: [0, 170],
  b: [0, 150, 200, 150],
  c: [0, 170, 200, 170, 200, 170],
  d: [0, 150, 200, 150, 200, 150, 200, 150],
};
const phones = new Set(); // respuestas abiertas de GET /events (Server-Sent Events) de la app Android

// Solo esta PC: conexión por loopback y sin pasar por Cloudflare (que siempre agrega cf-connecting-ip)
const isLocal = (req) =>
  ['127.0.0.1', '::1'].includes(cleanIp(req.socket.remoteAddress)) && !req.headers['cf-connecting-ip'];

const handler = async (req, res) => {
  try {
    const urlPath = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const ip = cleanIp(req.headers['cf-connecting-ip'] || req.socket.remoteAddress);
    if (urlPath === '/events') {
      if (!validKey(req.headers.authorization)) {
        log(`⛔ GET /events con clave inválida desde ${ip}`);
        return res.writeHead(401).end();
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no' });
      res.write(': conectado\n\n');
      phones.add(res);
      const ping = setInterval(() => res.write(': ping\n\n'), 25_000); // Cloudflare corta a los 100 s sin datos
      req.on('close', () => {
        clearInterval(ping);
        phones.delete(res);
        log(`📴 Celular desconectado de vibraciones: ${ip}  (activos: ${phones.size})`);
      });
      log(`📱 Celular escuchando vibraciones: ${ip}  (activos: ${phones.size})`);
      return;
    }
    const vibrate = urlPath.match(/^\/vibrate\/([a-d])$/);
    if (vibrate) {
      if (req.method !== 'POST') return res.writeHead(405, { Allow: 'POST' }).end();
      if (!isLocal(req)) return res.writeHead(403).end();
      for (const p of phones) p.write(`data: ${VIBRATIONS[vibrate[1]].join(',')}\n\n`);
      log(`📳 Vibración ${vibrate[1].toUpperCase()} → ${phones.size} celular(es)`);
      return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ phones: phones.size }));
    }
    if (urlPath === '/beep') {
      if (req.method !== 'POST') return res.writeHead(405, { Allow: 'POST' }).end();
      if (!validKey(req.headers.authorization)) {
        log(`⛔ POST /beep con clave inválida desde ${ip}`);
        return res.writeHead(401).end();
      }
      beep();
      log(`🔔 BEEP (HTTP) desde ${ip}`);
      return res.writeHead(204).end();
    }
    const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
    const filePath = path.resolve(PUBLIC_DIR, rel);

    // Evita path traversal (../../etc/passwd)
    if (!filePath.startsWith(PUBLIC_DIR + path.sep)) {
      res.writeHead(403).end('Forbidden');
      return;
    }
    const body = await readFile(filePath);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(body);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('No encontrado');
  }
};
const httpServer = TLS ? https.createServer(TLS, handler) : http.createServer(handler);

httpServer.listen(HTTP_PORT, HOST);

// ---------------------------------------------------------------------------
// Servidor WebSocket
// ---------------------------------------------------------------------------
const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  const ip = cleanIp(req.socket.remoteAddress);
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  log(`🔌 Cliente conectado: ${ip}  (activos: ${wss.clients.size})`);
  ws.send(JSON.stringify({ type: 'hello', at: Date.now() }));

  ws.on('message', (raw) => {
    const msg = raw.toString().trim();
    if (msg.toUpperCase() === 'BEEP') {
      beep();
      log(`🔔 BEEP desde ${ip}`);
      ws.send(JSON.stringify({ type: 'ack', at: Date.now() }));
    } else {
      log(`ℹ️  Mensaje ignorado de ${ip}: ${msg.slice(0, 80)}`);
    }
  });

  ws.on('close', (code) => {
    log(`❌ Cliente desconectado: ${ip}  (código ${code}, activos: ${wss.clients.size})`);
  });

  ws.on('error', (err) => log(`⚠️  Error con ${ip}: ${err.message}`));
});

// Heartbeat: cierra conexiones "zombis" (p. ej., el celular apagó el Wi-Fi sin avisar)
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_MS);
wss.on('close', () => clearInterval(heartbeat));

// ---------------------------------------------------------------------------
// Arranque: mostrar las IPs de la red local para abrir desde el celular
// ---------------------------------------------------------------------------
const lanIps = Object.values(os.networkInterfaces())
  .flat()
  .filter((i) => i && i.family === 'IPv4' && !i.internal)
  .map((i) => i.address);

console.log('\n  Selfie Beep listo');
const proto = TLS ? 'https' : 'http';
console.log(`  • Web App + WebSocket: ${proto}://localhost:${HTTP_PORT}`);
for (const ip of lanIps) console.log(`                        ${proto}://${ip}:${HTTP_PORT}   ← abrí esta en el celular`);
console.log(`  • POST /beep  con header  Authorization: Bearer <clave de beep-key.txt>`);
console.log(`  • Vibrar el celular:     http://localhost:${HTTP_PORT}/vibrar.html   (solo desde esta PC)\n`);

// Cierre prolijo con Ctrl+C
const shutdown = () => {
  log('Apagando servidores…');
  for (const p of phones) p.end();
  for (const ws of wss.clients) ws.close(1001, 'Servidor apagándose');
  wss.close();
  httpServer.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
