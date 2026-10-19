'use strict';
/**
 * Certificado ICP-Brasil A3: token USB ou cartão inteligente acessado via PKCS#11
 * (biblioteca do fabricante, ex.: SafeNet eToken "eTPKCS11.dll"/"libeTPkcs11.so",
 * Gemalto/Thales "gclib", Watchdata, Certisign "aetpkss1.dll").
 * A chave privada nunca sai do dispositivo: só o DigestInfo é enviado para assinatura.
 * Dependência opcional `pkcs11js`; sem ela, o módulo informa indisponibilidade.
 */
const forge = require('node-forge');
const { externalKey } = require('./pkcs7');

let pkcs11js = null;
try { pkcs11js = require('pkcs11js'); } catch { pkcs11js = null; }

function isAvailable() { return !!pkcs11js; }

function listSlots(libraryPath) {
  if (!pkcs11js) throw new Error('Módulo pkcs11js não instalado (npm install pkcs11js)');
  const mod = new pkcs11js.PKCS11();
  mod.load(libraryPath);
  mod.C_Initialize();
  try {
    return mod.C_GetSlotList(true).map((slot) => {
      const info = mod.C_GetTokenInfo(slot);
      return { slot: slot.toString('hex'), label: info.label.trim(), manufacturer: info.manufacturerID.trim(), serial: info.serialNumber.trim() };
    });
  } finally { mod.C_Finalize(); }
}

/**
 * Abre o token, faz login com o PIN e devolve um signatário compatível com signCms.
 * Chame signer.close() ao final para fazer logout e liberar o dispositivo.
 */
function pkcs11Signer({ libraryPath, pin, slotIndex = 0 }) {
  if (!pkcs11js) throw new Error('Módulo pkcs11js não instalado (npm install pkcs11js)');
  const mod = new pkcs11js.PKCS11();
  mod.load(libraryPath);
  mod.C_Initialize();
  const slots = mod.C_GetSlotList(true);
  if (!slots.length) { mod.C_Finalize(); throw new Error('Nenhum token/cartão encontrado'); }
  const session = mod.C_OpenSession(slots[slotIndex], pkcs11js.CKF_SERIAL_SESSION | pkcs11js.CKF_RW_SESSION);
  mod.C_Login(session, pkcs11js.CKU_USER, pin);

  // Certificados do token
  mod.C_FindObjectsInit(session, [{ type: pkcs11js.CKA_CLASS, value: pkcs11js.CKO_CERTIFICATE }]);
  const certObjs = mod.C_FindObjects(session, 16);
  mod.C_FindObjectsFinal(session);
  const certs = certObjs.map((h) => {
    const attrs = mod.C_GetAttributeValue(session, h, [{ type: pkcs11js.CKA_VALUE }, { type: pkcs11js.CKA_ID }]);
    const der = attrs[0].value.toString('binary');
    return { handle: h, id: attrs[1].value, cert: forge.pki.certificateFromAsn1(forge.asn1.fromDer(der)) };
  });
  // Escolhe o certificado com uso de assinatura digital (digitalSignature/nonRepudiation) e chave privada correspondente.
  const chosen = certs.find((c) => {
    const ku = c.cert.getExtension('keyUsage');
    return ku && (ku.digitalSignature || ku.nonRepudiation);
  }) || certs[0];
  if (!chosen) throw new Error('Nenhum certificado no token');

  mod.C_FindObjectsInit(session, [{ type: pkcs11js.CKA_CLASS, value: pkcs11js.CKO_PRIVATE_KEY }, { type: pkcs11js.CKA_ID, value: chosen.id }]);
  const keyObjs = mod.C_FindObjects(session, 1);
  mod.C_FindObjectsFinal(session);
  if (!keyObjs.length) throw new Error('Chave privada correspondente ao certificado não encontrada no token');
  const privKey = keyObjs[0];

  const key = externalKey((digestInfo) => {
    mod.C_SignInit(session, { mechanism: pkcs11js.CKM_RSA_PKCS }, privKey);
    return mod.C_Sign(session, digestInfo, Buffer.alloc(1024));
  });

  const { describeCert } = require('./pkcs7');
  return {
    key,
    certificate: chosen.cert,
    chain: certs.filter((c) => c !== chosen).map((c) => c.cert),
    tipo: 'A3 (PKCS#11)',
    descricao: describeCert(chosen.cert),
    close() { try { mod.C_Logout(session); mod.C_CloseSession(session); } finally { mod.C_Finalize(); } },
  };
}

module.exports = { isAvailable, listSlots, pkcs11Signer };
