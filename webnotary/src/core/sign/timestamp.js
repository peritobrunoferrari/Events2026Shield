'use strict';
/**
 * Carimbo do tempo RFC 3161 (Time-Stamp Protocol).
 * Gera o TimeStampReq, envia à Autoridade de Carimbo do Tempo (ACT ICP-Brasil ou
 * outra TSA) e interpreta o TimeStampResp, extraindo genTime, política e número de série.
 */
const crypto = require('node:crypto');
const forge = require('node-forge');
const { asn1, pki } = forge;

const OID_TSTINFO = '1.2.840.113549.1.9.16.1.4';

function buildTimestampRequest(hashHex, { nonce = true, certReq = true } = {}) {
  const parts = [
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.INTEGER, false, asn1.integerToDer(1).getBytes()),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
      asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
        asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(pki.oids.sha256).getBytes()),
        asn1.create(asn1.Class.UNIVERSAL, asn1.Type.NULL, false, ''),
      ]),
      asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OCTETSTRING, false, forge.util.hexToBytes(hashHex)),
    ]),
  ];
  let nonceHex = null;
  if (nonce) {
    const n = crypto.randomBytes(16);
    n[0] &= 0x7f; // inteiro positivo
    nonceHex = n.toString('hex');
    parts.push(asn1.create(asn1.Class.UNIVERSAL, asn1.Type.INTEGER, false, n.toString('binary')));
  }
  if (certReq) parts.push(asn1.create(asn1.Class.UNIVERSAL, asn1.Type.BOOLEAN, false, String.fromCharCode(0xff)));
  const req = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, parts);
  return { der: Buffer.from(asn1.toDer(req).getBytes(), 'binary'), nonceHex };
}

const STATUS = ['granted', 'grantedWithMods', 'rejection', 'waiting', 'revocationWarning', 'revocationNotification'];

/** Interpreta um TimeStampResp DER. Devolve { status, token (DER do ContentInfo), info }. */
function parseTimestampResponse(respDer) {
  const resp = asn1.fromDer(forge.util.createBuffer(respDer.toString('binary')));
  const statusInfo = resp.value[0];
  const status = statusInfo.value[0].value.charCodeAt(0);
  const out = { status: STATUS[status] || String(status), statusCode: status, token: null, info: null };
  if (statusInfo.value[1] && statusInfo.value[1].type === asn1.Type.SEQUENCE) {
    out.statusText = statusInfo.value[1].value.map((s) => s.value).join('; ');
  }
  if (resp.value[1]) {
    out.token = Buffer.from(asn1.toDer(resp.value[1]).getBytes(), 'binary');
    out.info = parseTimestampToken(out.token);
  }
  return out;
}

/** Extrai o TSTInfo de um TimeStampToken (ContentInfo/SignedData). */
function parseTimestampToken(tokenDer) {
  const ci = asn1.fromDer(forge.util.createBuffer(Buffer.from(tokenDer).toString('binary')));
  const signedData = ci.value[1].value[0];
  const encap = signedData.value[2]; // EncapsulatedContentInfo
  const eContentType = asn1.derToOid(encap.value[0].value);
  if (eContentType !== OID_TSTINFO) throw new Error('conteúdo não é TSTInfo: ' + eContentType);
  const octet = encap.value[1].value[0];
  const tst = asn1.fromDer(forge.util.createBuffer(octet.value));
  const [, policy, imprint, serial, genTime] = tst.value;
  const info = {
    policy: asn1.derToOid(policy.value),
    hashAlgorithm: asn1.derToOid(imprint.value[0].value[0].value),
    hashedMessage: forge.util.bytesToHex(imprint.value[1].value),
    serialNumber: forge.util.bytesToHex(serial.value),
    genTime: asn1.generalizedTimeToDate(genTime.value).toISOString(),
  };
  // TSA (certificado do carimbador), se incluído
  try {
    const certsSet = signedData.value.find((v) => v.tagClass === asn1.Class.CONTEXT_SPECIFIC && v.type === 0);
    if (certsSet && certsSet.value.length) {
      const cert = pki.certificateFromAsn1(certsSet.value[0]);
      const { describeCert } = require('./pkcs7');
      info.tsa = describeCert(cert);
    }
  } catch { /* opcional */ }
  return info;
}

/**
 * Solicita um carimbo do tempo para um hash SHA-256 (hex).
 * @param {string} url endpoint da ACT (HTTP POST application/timestamp-query)
 * @param {string} hashHex
 * @param {{ username?: string, password?: string, timeoutMs?: number }} [opts]
 */
async function requestTimestamp(url, hashHex, opts = {}) {
  const { der, nonceHex } = buildTimestampRequest(hashHex);
  const headers = { 'Content-Type': 'application/timestamp-query', Accept: 'application/timestamp-reply' };
  if (opts.username) headers.Authorization = 'Basic ' + Buffer.from(`${opts.username}:${opts.password || ''}`).toString('base64');
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), opts.timeoutMs || 20000);
  let res;
  try {
    res = await fetch(url, { method: 'POST', headers, body: der, signal: ctrl.signal });
  } finally { clearTimeout(t); }
  if (!res.ok) throw new Error(`ACT respondeu HTTP ${res.status}`);
  const respDer = Buffer.from(await res.arrayBuffer());
  const parsed = parseTimestampResponse(respDer);
  if (!parsed.token) throw new Error(`ACT recusou o carimbo: ${parsed.status} ${parsed.statusText || ''}`.trim());
  if (parsed.info.hashedMessage !== hashHex.toLowerCase()) throw new Error('ACT devolveu carimbo para hash diferente');
  return { ...parsed, request: der, nonceHex, url };
}

module.exports = { buildTimestampRequest, parseTimestampResponse, parseTimestampToken, requestTimestamp, OID_TSTINFO };
