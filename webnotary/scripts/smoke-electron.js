#!/usr/bin/env node
'use strict';
/**
 * Teste de fumaça do aplicativo real: abre o Electron com depuração remota, inicia uma
 * sessão sobre uma página local, captura, anota, encerra e verifica a pasta gerada.
 * Uso (Linux sem tela):  xvfb-run -a node scripts/smoke-electron.js
 */
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { verifySessionDir, renderVerification } = require('../src/core/verify');

const root = path.join(__dirname, '..');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wn-smoke-'));
const userData = path.join(tmp, 'userData');
fs.mkdirSync(userData, { recursive: true });
const page = path.join(tmp, 'pagina.html');
fs.writeFileSync(page, '<!doctype html><title>Página de teste WebNotary</title><h1>Conteúdo probatório</h1><img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==">');
// Certificado A1 de teste (autoassinado) para exercitar CAdES + PAdES dentro do app.
const { makeCert, makePfx } = require('../test/helpers');
const pfx = path.join(tmp, 'teste.pfx');
fs.writeFileSync(pfx, makePfx(makeCert({ cn: 'PERITO SMOKE:00000000000' }), 'senha-smoke'));
fs.writeFileSync(path.join(userData, 'config.json'), JSON.stringify({ pagina_inicial: 'file://' + page, pasta_sessoes: path.join(tmp, 'sessoes'), ip_publico_url: '', operador: { nome: 'Perito Smoke' }, certificado: { modo: 'A1', pfx } }));

const port = 9333 + Math.floor(Math.random() * 500);
const electron = require('electron');
const child = spawn(electron, ['--no-sandbox', '--disable-gpu', `--remote-debugging-port=${port}`, `--user-data-dir=${userData}`, root], { stdio: ['ignore', 'pipe', 'pipe'] });
let logs = '';
child.stdout.on('data', (d) => (logs += d));
child.stderr.on('data', (d) => (logs += d));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function cdpTargets() { return (await fetch(`http://127.0.0.1:${port}/json`)).json(); }

async function main() {
  let targets = [];
  for (let i = 0; i < 60 && !targets.some((t) => t.url.endsWith('index.html')); i++) { await sleep(500); try { targets = await cdpTargets(); } catch { /* ainda subindo */ } }
  const tb = targets.find((t) => t.url.endsWith('index.html'));
  if (!tb) throw new Error('janela da barra de ferramentas não encontrada: ' + JSON.stringify(targets));
  const ws = new WebSocket(tb.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0; const pending = new Map();
  ws.onmessage = (m) => { const msg = JSON.parse(m.data); if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); } };
  const evalJs = (expression) => new Promise((resolve) => { const i = ++id; pending.set(i, resolve); ws.send(JSON.stringify({ id: i, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } })); })
    .then((m) => { if (m.result.exceptionDetails) throw new Error(JSON.stringify(m.result.exceptionDetails)); return m.result.result.value; });

  await sleep(2500);
  const ini = await evalJs(`wn.sessaoIniciar({ finalidade: 'smoke test' })`);
  console.log('iniciar:', ini);
  if (!ini.ok) throw new Error(ini.erro);
  await sleep(6000); // navegação inicial + alguns trechos de vídeo (se a captura de tela funcionar no ambiente)
  console.log('capturar:', await evalJs(`wn.sessaoCapturar()`));
  console.log('nota:', await evalJs(`wn.sessaoNota('nota do teste de fumaça')`));
  await evalJs(`wn.nav('ir', ${JSON.stringify('file://' + page + '?segunda')})`);
  await sleep(2500);
  const errada = await evalJs(`wn.sessaoEncerrar({ observacoes: 'x', segredo: 'senha-errada' })`);
  console.log('senha errada (deve falhar e manter a sessão):', errada);
  if (errada.ok) throw new Error('senha errada deveria ser rejeitada');
  const fim = await evalJs(`wn.sessaoEncerrar({ observacoes: 'encerrado pelo smoke test', segredo: 'senha-smoke' })`);
  console.log('encerrar:', JSON.stringify(fim, null, 1));
  if (!fim.ok) throw new Error(fim.erro);
  const lista = await evalJs(`wn.sessoesListar()`);
  console.log('banco:', lista.map((s) => `${s.id} ${s.status} ${s.root_hash} eventos=${s.eventos}`));
  if (!fim.signedReport || !fim.signatureSha256) throw new Error('assinatura automática não ocorreu');
  const V = await verifySessionDir(fim.dir);
  process.stdout.write(renderVerification(V));
  console.log('arquivos:', fs.readdirSync(fim.dir).join(', '));
  const ev = fs.readFileSync(path.join(fim.dir, 'eventos.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l).type);
  console.log('tipos de evento:', [...new Set(ev)].join(', '));
  const vp = path.join(fim.dir, 'gravacao.webm');
  console.log('gravacao.webm bytes:', fs.existsSync(vp) ? fs.statSync(vp).size : 'AUSENTE');
  for (const l of fs.readFileSync(path.join(fim.dir, 'eventos.jsonl'), 'utf8').split('\n').filter(Boolean)) { const e = JSON.parse(l); if (/nota|video/.test(e.type)) console.log('  evento', e.type, e.data); }
  ws.close();
  if (!V.ok) throw new Error('verificação falhou');
}

main().then(() => { child.kill(); console.log('SMOKE OK'); process.exit(0); })
  .catch((e) => { child.kill(); console.error('SMOKE FALHOU:', e.message); console.error(logs.split('\n').filter((l) => !/dbus|gpu|vaapi|sandbox|MESA|libva|egl|ExperimentalWarning|trace-warnings/i.test(l)).join('\n')); process.exit(1); });
