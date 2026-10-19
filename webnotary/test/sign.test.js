'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { makeCert, makePfx, makeTimestampResponse } = require('./helpers');
const { signCms, verifyCms, externalKey, digestInfoDer } = require('../src/core/sign/pkcs7');
const { pkcs12Signer } = require('../src/core/sign/pkcs12');
const { buildTimestampRequest, parseTimestampResponse } = require('../src/core/sign/timestamp');

const signer = makeCert();

test('CAdES destacado: assina, verifica e detecta conteúdo alterado', async () => {
  const data = Buffer.from('{"manifesto":true}\n');
  const { der, signatureValue } = await signCms(data, { ...signer });
  assert.ok(der.length > 500);
  assert.strictEqual(signatureValue.length, 256);
  const ok = verifyCms(der, data);
  assert.ok(ok.ok, ok.erros.join(';'));
  assert.match(ok.signatario, /PERITO TESTE/);
  const bad = verifyCms(der, Buffer.from('outro'));
  assert.strictEqual(bad.ok, false);
  assert.match(bad.erros[0], /messageDigest/);
});

test('CAdES-T: carimbo do tempo embutido como atributo não assinado e validado contra a assinatura', async () => {
  const data = Buffer.from('conteudo');
  const { der, timestampToken } = await signCms(data, { ...signer, requestTimestamp: async (sig) => parseTimestampResponse(makeTimestampResponse(crypto.createHash('sha256').update(sig).digest('hex'))).token });
  assert.ok(timestampToken);
  const v = verifyCms(der, data);
  assert.ok(v.ok, v.erros.join(';'));
  assert.ok(v.carimbo.imprint_confere);
  assert.strictEqual(v.carimbo.policy, '2.16.76.1.6.2');
  // carimbo para hash errado é detectado
  const wrong = await signCms(data, { ...signer, requestTimestamp: async () => parseTimestampResponse(makeTimestampResponse('0'.repeat(64))).token });
  const v2 = verifyCms(wrong.der, data);
  assert.strictEqual(v2.ok, false);
  assert.ok(v2.erros.some((e) => /carimbo/.test(e)));
});

test('chave externa (simula token A3): DigestInfo + RSA PKCS#1 v1.5 produz assinatura válida', async () => {
  const pem = require('node-forge').pki.privateKeyToPem(signer.key);
  const nodeKey = crypto.createPrivateKey(pem);
  const key = externalKey((digestInfo) => crypto.privateEncrypt({ key: nodeKey, padding: crypto.constants.RSA_PKCS1_PADDING }, digestInfo));
  const data = Buffer.from('via token');
  const { der } = await signCms(data, { key, certificate: signer.certificate });
  assert.ok(verifyCms(der, data).ok);
  assert.strictEqual(digestInfoDer('\0'.repeat(32)).length, 51);
});

test('PKCS#12: carrega .pfx com senha, rejeita senha errada', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wn-pfx-'));
  const f = path.join(dir, 'cert.pfx');
  fs.writeFileSync(f, makePfx(signer, 'segredo'));
  const s = pkcs12Signer(f, 'segredo');
  assert.strictEqual(s.tipo, 'A1 (PKCS#12)');
  assert.match(s.descricao.cn, /PERITO TESTE/);
  assert.throws(() => pkcs12Signer(f, 'errada'));
});

test('RFC 3161: requisição DER bem formada e resposta interpretada', () => {
  const h = 'ab'.repeat(32);
  const { der, nonceHex } = buildTimestampRequest(h);
  assert.strictEqual(der[0], 0x30);
  assert.strictEqual(nonceHex.length, 32);
  const r = parseTimestampResponse(makeTimestampResponse(h, { genTime: new Date('2026-01-02T03:04:05Z') }));
  assert.strictEqual(r.status, 'granted');
  assert.strictEqual(r.info.hashedMessage, h);
  assert.strictEqual(r.info.genTime, '2026-01-02T03:04:05.000Z');
  assert.strictEqual(r.info.tsa.cn, 'ACT TESTE');
});
