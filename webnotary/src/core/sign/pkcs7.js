'use strict';
/**
 * Construção de assinaturas CMS/PKCS#7 (perfil CAdES-BES; CAdES-T quando há
 * carimbo do tempo) com chave plugável: certificado A1 (.pfx em software) ou
 * A3 (token/cartão via PKCS#11). A mesma rotina é usada para assinar o
 * manifesto (assinatura destacada .p7s) e o relatório PDF (PAdES, embutida).
 */
const forge = require('node-forge');
const { asn1, pki } = forge;

const OID_SIGNATURE_TIMESTAMP_TOKEN = '1.2.840.113549.1.9.16.2.14'; // id-aa-signatureTimeStampToken
const OID_SHA256 = pki.oids.sha256;

/**
 * Encodes DigestInfo (RFC 8017 §9.2) for RSASSA-PKCS1-v1_5 when the token only exposes raw RSA PKCS#1.
 * @param {string} digestBytes binary string of the SHA-256 digest
 */
function digestInfoDer(digestBytes, oid = OID_SHA256) {
  const di = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
      asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(oid).getBytes()),
      asn1.create(asn1.Class.UNIVERSAL, asn1.Type.NULL, false, ''),
    ]),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OCTETSTRING, false, digestBytes),
  ]);
  return asn1.toDer(di).getBytes();
}

/**
 * Adapta uma função de assinatura "bruta" (recebe o DER do DigestInfo, devolve a
 * assinatura RSA PKCS#1 v1.5) à interface `key.sign(md)` esperada pelo node-forge.
 * @param {(digestInfoDer: Buffer) => Buffer} rawSign
 */
function externalKey(rawSign) {
  return {
    sign(md) {
      const di = digestInfoDer(md.digest().getBytes(), pki.oids[md.algorithm] || OID_SHA256);
      const sig = rawSign(Buffer.from(di, 'binary'));
      return Buffer.from(sig).toString('binary');
    },
  };
}

/**
 * Assina `data` e devolve o CMS SignedData em DER.
 * @param {Buffer} data conteúdo a assinar
 * @param {object} o
 * @param {forge.pki.Certificate} o.certificate certificado do signatário
 * @param {forge.pki.Certificate[]} [o.chain] cadeia (intermediárias/raiz)
 * @param {object} o.key chave forge ou objeto com sign(md) (ver externalKey)
 * @param {boolean} [o.detached=true]
 * @param {Date} [o.signingTime]
 * @param {Buffer} [o.timestampToken] TimeStampToken (ContentInfo DER) a embutir como atributo não assinado
 * @param {(signatureValue: Buffer) => Promise<Buffer>} [o.requestTimestamp] callback que obtém o token de carimbo sobre o valor da assinatura
 */
async function signCms(data, o) {
  const p7 = forge.pkcs7.createSignedData();
  p7.content = forge.util.createBuffer(data.toString('binary'));
  p7.addCertificate(o.certificate);
  for (const c of o.chain || []) p7.addCertificate(c);
  p7.addSigner({
    key: o.key,
    certificate: o.certificate,
    digestAlgorithm: OID_SHA256,
    authenticatedAttributes: [
      { type: pki.oids.contentType, value: pki.oids.data },
      { type: pki.oids.messageDigest },
      { type: pki.oids.signingTime, value: o.signingTime || new Date() },
    ],
  });
  p7.sign({ detached: o.detached !== false });

  const contentInfo = p7.toAsn1();
  const signerInfo = getFirstSignerInfo(contentInfo);
  const signatureValue = Buffer.from(signerInfo.value.find((v, i) => i >= 4 && v.type === asn1.Type.OCTETSTRING).value, 'binary');

  let token = o.timestampToken || null;
  if (!token && o.requestTimestamp) token = await o.requestTimestamp(signatureValue);
  if (token) addUnsignedTimestamp(signerInfo, token);

  return { der: Buffer.from(asn1.toDer(contentInfo).getBytes(), 'binary'), signatureValue, timestampToken: token };
}

function getFirstSignerInfo(contentInfo) {
  const signedData = contentInfo.value[1].value[0]; // [0] EXPLICIT -> SignedData
  const signerInfos = signedData.value[signedData.value.length - 1]; // SET OF SignerInfo
  return signerInfos.value[0];
}

function addUnsignedTimestamp(signerInfo, tokenDer) {
  const tokenAsn1 = asn1.fromDer(forge.util.createBuffer(Buffer.from(tokenDer).toString('binary')));
  const attr = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(OID_SIGNATURE_TIMESTAMP_TOKEN).getBytes()),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SET, true, [tokenAsn1]),
  ]);
  signerInfo.value.push(asn1.create(asn1.Class.CONTEXT_SPECIFIC, 1, true, [attr]));
}

/**
 * Verifica um CMS destacado contra `data`: confere messageDigest, assinatura RSA
 * sobre os atributos assinados e devolve dados do certificado.
 */
function verifyCms(cmsDer, data) {
  const contentInfo = asn1.fromDer(forge.util.createBuffer(cmsDer.toString('binary')));
  const msg = forge.pkcs7.messageFromAsn1(contentInfo);
  const signerInfo = getFirstSignerInfo(contentInfo);
  const result = { ok: false, erros: [], signatario: null, certificado: null, assinado_em: null, carimbo: null };

  // Localiza o certificado do signatário por issuer+serial.
  const sidAsn1 = signerInfo.value[1]; // IssuerAndSerialNumber
  const serialHex = forge.util.bytesToHex(sidAsn1.value[1].value);
  const cert = msg.certificates.find((c) => c.serialNumber.replace(/^0+/, '') === serialHex.replace(/^0+/, '')) || msg.certificates[0];
  if (!cert) { result.erros.push('certificado do signatário ausente'); return result; }
  result.certificado = describeCert(cert);
  result.signatario = result.certificado.subject;

  // Atributos assinados: SignerInfo.value[3] ([0] IMPLICIT SET OF Attribute)
  const authAttrs = signerInfo.value.find((v) => v.tagClass === asn1.Class.CONTEXT_SPECIFIC && v.type === 0);
  if (!authAttrs) { result.erros.push('atributos assinados ausentes'); return result; }
  let messageDigest = null;
  for (const attr of authAttrs.value) {
    const oid = asn1.derToOid(attr.value[0].value);
    const val = attr.value[1].value[0];
    if (oid === pki.oids.messageDigest) messageDigest = forge.util.bytesToHex(val.value);
    if (oid === pki.oids.signingTime) result.assinado_em = (asn1.utcTimeToDate ? asn1.utcTimeToDate(val.value) : null)?.toISOString?.() || null;
  }
  const actual = forge.md.sha256.create().update(data.toString('binary')).digest().toHex();
  if (messageDigest !== actual) result.erros.push(`messageDigest divergente (esperado ${actual}, no CMS ${messageDigest})`);

  // Verifica a assinatura RSA sobre o SET OF Attribute (recodificado com tag SET).
  const setAsn1 = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SET, true, authAttrs.value);
  const attrsDer = asn1.toDer(setAsn1).getBytes();
  const md = forge.md.sha256.create().update(attrsDer);
  const sigOctet = signerInfo.value.find((v, i) => i >= 4 && v.tagClass === asn1.Class.UNIVERSAL && v.type === asn1.Type.OCTETSTRING);
  try {
    const okSig = cert.publicKey.verify(md.digest().getBytes(), sigOctet.value);
    if (!okSig) result.erros.push('assinatura RSA inválida');
  } catch (e) { result.erros.push('falha ao verificar assinatura: ' + e.message); }

  // Carimbo do tempo (atributo não assinado), se existir.
  const unsigned = signerInfo.value.find((v) => v.tagClass === asn1.Class.CONTEXT_SPECIFIC && v.type === 1);
  if (unsigned) {
    for (const attr of unsigned.value) {
      if (asn1.derToOid(attr.value[0].value) === OID_SIGNATURE_TIMESTAMP_TOKEN) {
        try {
          const { parseTimestampToken } = require('./timestamp');
          const tokenDer = Buffer.from(asn1.toDer(attr.value[1].value[0]).getBytes(), 'binary');
          const info = parseTimestampToken(tokenDer);
          const sigHash = forge.md.sha256.create().update(sigOctet.value).digest().toHex();
          info.imprint_confere = info.hashedMessage === sigHash;
          if (!info.imprint_confere) result.erros.push('carimbo do tempo não corresponde à assinatura');
          result.carimbo = info;
        } catch (e) { result.erros.push('carimbo do tempo ilegível: ' + e.message); }
      }
    }
  }
  result.ok = result.erros.length === 0;
  return result;
}

function describeCert(cert) {
  const attr = (subj, name) => { const a = subj.getField(name); return a ? a.value : null; };
  return {
    subject: cert.subject.attributes.map((a) => `${a.shortName || a.name}=${a.value}`).join(', '),
    cn: attr(cert.subject, 'CN'),
    issuer: cert.issuer.attributes.map((a) => `${a.shortName || a.name}=${a.value}`).join(', '),
    issuer_cn: attr(cert.issuer, 'CN'),
    serial: cert.serialNumber,
    valido_de: cert.validity.notBefore.toISOString(),
    valido_ate: cert.validity.notAfter.toISOString(),
    sha256: forge.md.sha256.create().update(asn1.toDer(pki.certificateToAsn1(cert)).getBytes()).digest().toHex(),
  };
}

module.exports = { signCms, verifyCms, externalKey, digestInfoDer, describeCert, OID_SIGNATURE_TIMESTAMP_TOKEN };
