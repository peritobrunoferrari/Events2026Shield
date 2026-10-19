#!/usr/bin/env node
'use strict';
/**
 * Servidor remoto de VERIFICAÇÃO do WebNotary (somente Node padrão, sem dependências).
 *
 * Princípio: a prova fica no computador do perito. Aqui chegam apenas hashes.
 * Armazenamento: um arquivo de texto (uma linha por registro, campos separados por TAB).
 *
 * Endpoints:
 *   POST /api/register        { session_id, root_hash, manifest_sha256, signature_sha256, report_sha256, operator, timestamp_gen_time }
 *                             (Authorization: Bearer <WEBNOTARY_API_TOKEN>, se configurado)
 *   GET  /api/verify/<hash>   procura o hash (raiz, manifesto, assinatura ou relatório)
 *   GET  /api/health
 *   GET  /                    página simples de verificação
 *
 * Variáveis de ambiente:
 *   PORT (8787), HOST (0.0.0.0), WEBNOTARY_REGISTRY_FILE (./registry.txt),
 *   WEBNOTARY_API_TOKEN (opcional; exige token para registrar),
 *   WEBNOTARY_SECRET (segredo do recibo HMAC; gerado e salvo em ./registry.secret se ausente),
 *   PUBLIC_URL (URL pública para compor o link de verificação)
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const FILE = path.resolve(process.env.WEBNOTARY_REGISTRY_FILE || path.join(process.cwd(), 'registry.txt'));
const TOKEN = process.env.WEBNOTARY_API_TOKEN || '';
const PUBLIC_URL = (process.env.PUBLIC_URL || `http://localhost:${PORT}`).replace(/\/+$/, '');
const HEX64 = /^[0-9a-f]{64}$/;

function loadSecret() {
  if (process.env.WEBNOTARY_SECRET) return process.env.WEBNOTARY_SECRET;
  const f = FILE + '.secret';
  if (fs.existsSync(f)) return fs.readFileSync(f, 'utf8').trim();
  const s = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(f, s, { mode: 0o600 });
  return s;
}
const SECRET = loadSecret();
const FIELDS = ['registered_at', 'root_hash', 'session_id', 'manifest_sha256', 'signature_sha256', 'report_sha256', 'operator', 'timestamp_gen_time', 'receipt'];

function receiptFor(rootHash, registeredAt) {
  return crypto.createHmac('sha256', SECRET).update(`${rootHash}|${registeredAt}`).digest('hex');
}
function clean(v) { return String(v ?? '').replace(/[\t\r\n]/g, ' ').trim(); }

function appendRecord(rec) {
  if (!fs.existsSync(FILE)) fs.writeFileSync(FILE, '# ' + FIELDS.join('\t') + '\n');
  fs.appendFileSync(FILE, FIELDS.map((f) => clean(rec[f])).join('\t') + '\n');
}
function readRecords() {
  if (!fs.existsSync(FILE)) return [];
  return fs.readFileSync(FILE, 'utf8').split('\n').filter((l) => l && !l.startsWith('#')).map((l) => {
    const cols = l.split('\t');
    return Object.fromEntries(FIELDS.map((f, i) => [f, cols[i] || '']));
  });
}
function findHash(h) {
  h = h.toLowerCase();
  return readRecords().filter((r) => [r.root_hash, r.manifest_sha256, r.signature_sha256, r.report_sha256].includes(h))
    .map((r) => ({ ...r, receipt_valid: receiptFor(r.root_hash, r.registered_at) === r.receipt }));
}

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(obj));
}
function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > limit) { reject(new Error('corpo muito grande')); req.destroy(); } });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

const PAGE = `<!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>WebNotary - Verificação</title>
<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:40px auto;padding:0 16px;color:#222}input{width:100%;padding:10px;font-family:monospace;font-size:14px}button{padding:10px 18px;margin-top:8px}pre{background:#f4f4f4;padding:12px;overflow:auto}.ok{color:#1b5e20}.no{color:#b71c1c}</style>
<h1>WebNotary - Verificação de hash</h1>
<p>Informe o <b>hash raiz</b> da sessão (ou o SHA-256 do manifesto, da assinatura ou do relatório). Este servidor guarda apenas hashes; o conteúdo da prova permanece com o perito.</p>
<input id="h" placeholder="hash SHA-256 (64 caracteres hexadecimais)"><button onclick="go()">Verificar</button>
<div id="out"></div>
<script>async function go(){const h=document.getElementById('h').value.trim().toLowerCase();const o=document.getElementById('out');if(!/^[0-9a-f]{64}$/.test(h)){o.innerHTML='<p class=no>Hash inválido.</p>';return}
const r=await fetch('/api/verify/'+h);const j=await r.json();if(!j.found){o.innerHTML='<p class=no>Hash NÃO encontrado neste servidor.</p>';return}
o.innerHTML='<p class=ok>Hash ENCONTRADO. Registros: '+j.records.length+'</p><pre>'+JSON.stringify(j.records,null,2).replace(/</g,'&lt;')+'</pre>'}</script></html>`;

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  try {
    if (req.method === 'GET' && url.pathname === '/api/health') return json(res, 200, { ok: true, records: readRecords().length });
    if (req.method === 'GET' && url.pathname === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(PAGE); }
    if (req.method === 'GET' && url.pathname.startsWith('/api/verify/')) {
      const h = decodeURIComponent(url.pathname.slice('/api/verify/'.length)).toLowerCase();
      if (!HEX64.test(h)) return json(res, 400, { error: 'hash inválido' });
      const records = findHash(h);
      return json(res, 200, { hash: h, found: records.length > 0, records, server_time: new Date().toISOString() });
    }
    if (req.method === 'POST' && url.pathname === '/api/register') {
      if (TOKEN && req.headers.authorization !== `Bearer ${TOKEN}`) return json(res, 401, { error: 'token inválido' });
      let body;
      try { body = JSON.parse(await readBody(req)); } catch { return json(res, 400, { error: 'JSON inválido' }); }
      const root = String(body.root_hash || '').toLowerCase();
      if (!HEX64.test(root)) return json(res, 400, { error: 'root_hash inválido' });
      for (const f of ['manifest_sha256', 'signature_sha256', 'report_sha256']) {
        if (body[f] && !HEX64.test(String(body[f]).toLowerCase())) return json(res, 400, { error: `${f} inválido` });
      }
      const registered_at = new Date().toISOString();
      const rec = {
        registered_at, root_hash: root, session_id: body.session_id, manifest_sha256: (body.manifest_sha256 || '').toLowerCase(),
        signature_sha256: (body.signature_sha256 || '').toLowerCase(), report_sha256: (body.report_sha256 || '').toLowerCase(),
        operator: body.operator, timestamp_gen_time: body.timestamp_gen_time, receipt: receiptFor(root, registered_at),
      };
      appendRecord(rec);
      return json(res, 201, { registered_at, receipt: rec.receipt, root_hash: root, verify_url: `${PUBLIC_URL}/api/verify/${root}`, page_url: `${PUBLIC_URL}/` });
    }
    json(res, 404, { error: 'não encontrado' });
  } catch (e) {
    json(res, 500, { error: e.message });
  }
});

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`WebNotary verify-server em http://${HOST}:${PORT}  (registro: ${FILE}; token: ${TOKEN ? 'exigido' : 'não exigido'})`);
  });
}

module.exports = { server, receiptFor, findHash, appendRecord, FIELDS };
