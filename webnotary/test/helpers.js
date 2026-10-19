'use strict';
/** Utilitários de teste: gera certificado autoassinado, .pfx e um TimeStampToken sintético. */
const forge = require('node-forge');
const { asn1, pki } = forge;

function makeCert({ cn = 'PERITO TESTE:00000000000', days = 365, keyUsage = true } = {}) {
  const keys = pki.rsa.generateKeyPair(2048);
  const cert = pki.createCertificate();
  cert.publicKey = keys.publicKey;
  cert.serialNumber = '01' + forge.util.bytesToHex(forge.random.getBytesSync(8));
  cert.validity.notBefore = new Date(Date.now() - 60000);
  cert.validity.notAfter = new Date(Date.now() + days * 86400000);
  const attrs = [{ name: 'commonName', value: cn }, { name: 'countryName', value: 'BR' }, { name: 'organizationName', value: 'ICP-Brasil (TESTE)' }];
  cert.setSubject(attrs);
  cert.setIssuer(attrs);
  const exts = [{ name: 'basicConstraints', cA: false }];
  if (keyUsage) exts.push({ name: 'keyUsage', digitalSignature: true, nonRepudiation: true });
  cert.setExtensions(exts);
  cert.sign(keys.privateKey, forge.md.sha256.create());
  return { key: keys.privateKey, certificate: cert, chain: [] };
}

function makePfx(signer, password) {
  const p12 = forge.pkcs12.toPkcs12Asn1(signer.key, [signer.certificate], password, { algorithm: '3des' });
  return Buffer.from(asn1.toDer(p12).getBytes(), 'binary');
}

/** Monta um TimeStampResp "granted" assinado por uma TSA de teste para o hash dado. */
function makeTimestampResponse(hashHex, { genTime = new Date(), tsa } = {}) {
  tsa = tsa || makeCert({ cn: 'ACT TESTE' });
  const tstInfo = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.INTEGER, false, asn1.integerToDer(1).getBytes()),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer('2.16.76.1.6.2').getBytes()),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
      asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
        asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OID, false, asn1.oidToDer(pki.oids.sha256).getBytes()),
        asn1.create(asn1.Class.UNIVERSAL, asn1.Type.NULL, false, ''),
      ]),
      asn1.create(asn1.Class.UNIVERSAL, asn1.Type.OCTETSTRING, false, forge.util.hexToBytes(hashHex)),
    ]),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.INTEGER, false, asn1.integerToDer(12345).getBytes()),
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.GENERALIZEDTIME, false, asn1.dateToGeneralizedTime(genTime)),
  ]);
  const tstDer = asn1.toDer(tstInfo).getBytes();
  const p7 = forge.pkcs7.createSignedData();
  p7.content = forge.util.createBuffer(tstDer);
  p7.addCertificate(tsa.certificate);
  p7.addSigner({ key: tsa.key, certificate: tsa.certificate, digestAlgorithm: pki.oids.sha256,
    authenticatedAttributes: [{ type: pki.oids.contentType, value: '1.2.840.113549.1.9.16.1.4' }, { type: pki.oids.messageDigest }, { type: pki.oids.signingTime, value: genTime }] });
  p7.sign();
  const ci = p7.toAsn1();
  // Troca o eContentType de "data" para id-ct-TSTInfo
  const signedData = ci.value[1].value[0];
  signedData.value[2].value[0].value = asn1.oidToDer('1.2.840.113549.1.9.16.1.4').getBytes();
  const resp = asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [
    asn1.create(asn1.Class.UNIVERSAL, asn1.Type.SEQUENCE, true, [asn1.create(asn1.Class.UNIVERSAL, asn1.Type.INTEGER, false, asn1.integerToDer(0).getBytes())]),
    ci,
  ]);
  return Buffer.from(asn1.toDer(resp).getBytes(), 'binary');
}

module.exports = { makeCert, makePfx, makeTimestampResponse };
