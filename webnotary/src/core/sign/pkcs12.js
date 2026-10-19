'use strict';
/**
 * Certificado ICP-Brasil A1: arquivo .pfx/.p12 protegido por senha.
 * A senha é pedida ao operador no momento da assinatura e nunca é gravada.
 */
const fs = require('node:fs');
const forge = require('node-forge');

function loadPkcs12(file, password) {
  const der = fs.readFileSync(file).toString('binary');
  const p12Asn1 = forge.asn1.fromDer(der);
  const p12 = forge.pkcs12.pkcs12FromAsn1(p12Asn1, false, password);

  const keyBags = p12.getBags({ bagType: forge.pki.oids.pkcs8ShroudedKeyBag })[forge.pki.oids.pkcs8ShroudedKeyBag] || [];
  const plainKeyBags = p12.getBags({ bagType: forge.pki.oids.keyBag })[forge.pki.oids.keyBag] || [];
  const certBags = p12.getBags({ bagType: forge.pki.oids.certBag })[forge.pki.oids.certBag] || [];
  const key = (keyBags[0] || plainKeyBags[0] || {}).key;
  if (!key) throw new Error('Chave privada não encontrada no arquivo .pfx');

  // Certificado do titular = aquele cuja chave pública corresponde à chave privada.
  const certs = certBags.map((b) => b.cert).filter(Boolean);
  const keyMod = key.n.toString(16);
  const certificate = certs.find((c) => c.publicKey && c.publicKey.n.toString(16) === keyMod) || certs[0];
  if (!certificate) throw new Error('Certificado não encontrado no arquivo .pfx');
  const chain = certs.filter((c) => c !== certificate);
  return { key, certificate, chain };
}

/** Cria um signatário { key, certificate, chain, descricao } a partir de um .pfx. */
function pkcs12Signer(file, password) {
  const s = loadPkcs12(file, password);
  const { describeCert } = require('./pkcs7');
  return { ...s, tipo: 'A1 (PKCS#12)', descricao: describeCert(s.certificate) };
}

module.exports = { loadPkcs12, pkcs12Signer };
