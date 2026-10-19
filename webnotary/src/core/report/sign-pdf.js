'use strict';
/**
 * Assinatura PAdES (adbe.pkcs7.detached / ETSI.CAdES.detached) do relatório PDF,
 * executada automaticamente dentro do aplicativo ao encerrar a sessão, com o
 * mesmo signatário (A1 ou A3) usado no manifesto. Opcionalmente embute carimbo
 * do tempo (PAdES-T).
 */
const { SignPdf, Signer } = require('@signpdf/signpdf');
const { plainAddPlaceholder } = require('@signpdf/placeholder-plain');
const { signCms } = require('../sign/pkcs7');

class CmsSigner extends Signer {
  constructor(signer, { requestTimestamp } = {}) {
    super();
    this.signer = signer;
    this.requestTimestamp = requestTimestamp;
    this.lastTimestampToken = null;
  }
  async sign(pdfBuffer, signingTime) {
    const r = await signCms(pdfBuffer, {
      key: this.signer.key,
      certificate: this.signer.certificate,
      chain: this.signer.chain,
      detached: true,
      signingTime: signingTime || new Date(),
      requestTimestamp: this.requestTimestamp,
    });
    this.lastTimestampToken = r.timestampToken;
    return r.der;
  }
}

/**
 * @param {Buffer} pdfBuffer PDF não assinado
 * @param {object} signer { key, certificate, chain, descricao }
 * @param {object} [opts] { reason, location, contactInfo, name, requestTimestamp }
 * @returns {Promise<{ pdf: Buffer, timestampToken: Buffer|null }>}
 */
async function signReportPdf(pdfBuffer, signer, opts = {}) {
  const withPlaceholder = plainAddPlaceholder({
    pdfBuffer,
    reason: opts.reason || 'Ata de registro de sessão web - WebNotary',
    contactInfo: opts.contactInfo || signer.descricao?.cn || '',
    name: opts.name || signer.descricao?.cn || 'WebNotary',
    location: opts.location || 'Brasil',
    signatureLength: opts.signatureLength || 16384,
    subFilter: 'ETSI.CAdES.detached',
  });
  const cmsSigner = new CmsSigner(signer, { requestTimestamp: opts.requestTimestamp });
  const pdf = await new SignPdf().sign(withPlaceholder, cmsSigner);
  return { pdf, timestampToken: cmsSigner.lastTimestampToken };
}

/** Extrai a assinatura e o ByteRange de um PDF assinado e verifica o CMS sobre o conteúdo. */
function verifySignedPdf(pdfBuffer) {
  const { verifyCms } = require('../sign/pkcs7');
  const txt = pdfBuffer.toString('latin1');
  const brMatch = txt.match(/\/ByteRange\s*\[\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*\]/);
  if (!brMatch) return { ok: false, erros: ['PDF sem assinatura (ByteRange ausente)'] };
  const [a, b, c, d] = brMatch.slice(1).map(Number);
  const contentsIdx = txt.indexOf('/Contents', brMatch.index);
  const hexStart = txt.indexOf('<', contentsIdx) + 1;
  const hexEnd = txt.indexOf('>', hexStart);
  const hex = txt.slice(hexStart, hexEnd).replace(/\s+/g, '').replace(/0+$/, '');
  const cms = Buffer.from(hex.length % 2 ? hex + '0' : hex, 'hex');
  const signed = Buffer.concat([pdfBuffer.subarray(a, a + b), pdfBuffer.subarray(c, c + d)]);
  const r = verifyCms(cms, signed);
  r.byteRange = [a, b, c, d];
  return r;
}

module.exports = { signReportPdf, verifySignedPdf, CmsSigner };
