'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const { makeCert, makeTimestampResponse } = require('./helpers');
const { describeCert } = require('../src/core/sign/pkcs7');
const { AppDatabase } = require('../src/core/db');
const { RecordingSession } = require('../src/core/session');
const { verifySessionDir, renderVerification } = require('../src/core/verify');
const { verifySignedPdf } = require('../src/core/report/sign-pdf');
const { parseTimestampResponse } = require('../src/core/sign/timestamp');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wn-sess-'));
process.env.WEBNOTARY_REGISTRY_FILE = path.join(tmp, 'registry.txt');
process.env.WEBNOTARY_API_TOKEN = 'tok';
const { server } = require('../server/verify-server');

let registryUrl, tsaUrl, tsaServer;
test.before(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  registryUrl = `http://127.0.0.1:${server.address().port}`;
  // ACT falsa: lê o TimeStampReq, extrai o hash e responde com um token válido.
  tsaServer = http.createServer((req, res) => {
    const chunks = []; req.on('data', (c) => chunks.push(c)); req.on('end', () => {
      const forge = require('node-forge');
      const reqAsn1 = forge.asn1.fromDer(forge.util.createBuffer(Buffer.concat(chunks).toString('binary')));
      const hashHex = forge.util.bytesToHex(reqAsn1.value[1].value[1].value);
      res.writeHead(200, { 'Content-Type': 'application/timestamp-reply' });
      res.end(makeTimestampResponse(hashHex));
    });
  });
  await new Promise((r) => tsaServer.listen(0, '127.0.0.1', r));
  tsaUrl = `http://127.0.0.1:${tsaServer.address().port}/tsa`;
});
test.after(() => { server.close(); tsaServer.close(); });

test('sessão completa: artefatos, vídeo, manifesto, CAdES-T, PDF PAdES automático, registro remoto e verificação', async () => {
  const db = new AppDatabase(path.join(tmp, 'app.db'));
  const signer = makeCert(); signer.descricao = describeCert(signer.certificate); signer.tipo = 'A1 (teste)';
  const s = new RecordingSession({ baseDir: path.join(tmp, 'sessoes'), db, operator: { nome: 'Perito Teste', documento: '000' }, purpose: 'teste automatizado', environment: { os: 'test' }, appVersion: '0.1.0' }).start();
  s.addUrl('https://web.whatsapp.com/', 'WhatsApp');
  s.saveArtifact('captura', 'whatsapp.png', Buffer.from('fake-png'), { url: 'https://web.whatsapp.com/' });
  s.saveArtifact('midia', 'foto.jpg', Buffer.alloc(200, 7), { url: 'https://mmg.whatsapp.net/x.jpg', content_type: 'image/jpeg' });
  s.logNetwork({ tipo: 'resposta', url: 'https://web.whatsapp.com/', status: 200 });
  s.startVideo({ fonte: 'tela', audio: 'sistema' });
  s.appendVideoChunk(Buffer.alloc(5000, 1)); s.appendVideoChunk(Buffer.alloc(5000, 2));
  s.addNote('conversa com o número X exibida');
  const anexo = path.join(tmp, 'laudo-anterior.txt'); fs.writeFileSync(anexo, 'anexo');
  await s.attachFile(anexo);

  const msgs = [];
  const r = await s.finalize({ signer, tsaUrl, registry: { url: registryUrl, token: 'tok' }, notes: 'fim', onProgress: (m) => msgs.push(m) });
  assert.deepStrictEqual(r.warnings, []);
  assert.strictEqual(r.signedReport, 'relatorio-assinado.pdf');
  assert.ok(r.timestampInfo && r.timestampInfo.info.genTime);
  assert.ok(r.registryReceipt && r.registryReceipt.receipt);
  for (const f of ['gravacao.webm', 'eventos.jsonl', 'manifesto.json', 'manifesto.txt', 'manifesto.json.p7s', 'carimbo-manifesto.tsr', 'relatorio.pdf', 'relatorio-assinado.pdf', 'recibo-registro.json', 'VERIFICACAO.txt', 'rede/trafego.jsonl', 'anexos/001-laudo-anterior.txt'])
    assert.ok(fs.existsSync(path.join(r.dir, f)), f + ' deve existir');
  assert.strictEqual(fs.statSync(path.join(r.dir, 'gravacao.webm')).size, 10000);

  // Banco: hash raiz gravado e eventos íntegros
  const row = db.getSession(s.id);
  assert.strictEqual(row.status, 'selada');
  assert.strictEqual(row.root_hash, r.rootHash);
  assert.strictEqual(row.timestamp_info.info.genTime, r.timestampInfo.info.genTime);
  assert.ok(db.listEvents(s.id).length >= 9);

  // Relatório assinado: PAdES válido com carimbo
  const pdfV = verifySignedPdf(fs.readFileSync(path.join(r.dir, 'relatorio-assinado.pdf')));
  assert.ok(pdfV.ok, pdfV.erros.join(';'));
  assert.ok(pdfV.carimbo && pdfV.carimbo.imprint_confere);

  // Verificação completa
  const V = await verifySessionDir(r.dir, { registryUrl });
  assert.ok(V.ok, renderVerification(V));
  assert.ok(V.etapas.every((e) => e.ok));

  // Adulteração do vídeo e de um evento são detectadas
  fs.appendFileSync(path.join(r.dir, 'gravacao.webm'), 'x');
  const V2 = await verifySessionDir(r.dir);
  assert.strictEqual(V2.ok, false);
  assert.ok(V2.erros.some((e) => /gravacao.webm/.test(e)));
  const evs = fs.readFileSync(path.join(r.dir, 'eventos.jsonl'), 'utf8').split('\n').filter(Boolean);
  evs[2] = evs[2].replace('"type":"', '"type":"X');
  fs.writeFileSync(path.join(r.dir, 'eventos.jsonl'), evs.join('\n') + '\n');
  const V3 = await verifySessionDir(r.dir);
  assert.ok(V3.erros.some((e) => /cadeia de eventos/.test(e)));
  db.close();
});

test('sessão sem certificado: só hash + carimbo do manifesto + registro', async () => {
  const db = new AppDatabase(path.join(tmp, 'app2.db'));
  const s = new RecordingSession({ baseDir: path.join(tmp, 'sessoes2'), db }).start();
  s.saveArtifact('captura', 'a.png', Buffer.from('a'));
  const r = await s.finalize({ tsaUrl, registry: { url: registryUrl, token: 'tok' } });
  assert.deepStrictEqual(r.warnings, ['Nenhum vídeo foi gravado nesta sessão (captura de tela indisponível ou permissão negada).']);
  assert.strictEqual(r.signatureSha256, null);
  assert.strictEqual(r.signedReport, null);
  assert.ok(fs.existsSync(path.join(r.dir, 'carimbo-manifesto.tsr')));
  const V = await verifySessionDir(r.dir, { registryUrl });
  assert.ok(V.ok, renderVerification(V));
  db.close();
});

test('servidor de verificação: rejeita token inválido e hash malformado; encontra hash registrado', async () => {
  const bad = await fetch(`${registryUrl}/api/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ root_hash: 'a'.repeat(64) }) });
  assert.strictEqual(bad.status, 401);
  const malformed = await fetch(`${registryUrl}/api/register`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' }, body: JSON.stringify({ root_hash: 'zz' }) });
  assert.strictEqual(malformed.status, 400);
  const h = crypto.randomBytes(32).toString('hex');
  const ok = await fetch(`${registryUrl}/api/register`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' }, body: JSON.stringify({ root_hash: h, session_id: 'S' }) });
  assert.strictEqual(ok.status, 201);
  const v = await (await fetch(`${registryUrl}/api/verify/${h}`)).json();
  assert.strictEqual(v.found, true);
  assert.strictEqual(v.records[0].receipt_valid, true);
  const nf = await (await fetch(`${registryUrl}/api/verify/${'0'.repeat(64)}`)).json();
  assert.strictEqual(nf.found, false);
  const page = await (await fetch(`${registryUrl}/`)).text();
  assert.match(page, /Verificação de hash/);
});
