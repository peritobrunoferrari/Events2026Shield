'use strict';
/**
 * Captura forense da aba navegada, via Chrome DevTools Protocol (webContents.debugger):
 *  - tráfego de rede (requisições/respostas, IP remoto, TLS) -> rede/trafego.jsonl
 *  - certificados TLS por origem -> rede/cert-<host>.pem + evento
 *  - resolução DNS por host -> evento
 *  - mídias (imagem/vídeo/áudio) recebidas pela rede -> midia/
 *  - screenshot e página MHTML a cada navegação concluída
 */
const fs = require('node:fs');
const path = require('node:path');
const dns = require('node:dns').promises;
const forge = require('node-forge');
const { describeCert } = require('../core/sign/pkcs7');
const { hashBuffer } = require('../core/hashing');

const MEDIA_RE = /^(image|video|audio)\//i;

class TabCapture {
  constructor(webContents, session, config) {
    this.wc = webContents;
    this.session = session;
    this.config = config;
    this.origins = new Set();
    this.hosts = new Set();
    this.savedMedia = new Set();
    this.responses = new Map(); // requestId -> {url, mimeType, size}
    this.attached = false;
  }

  async attach() {
    const dbg = this.wc.debugger;
    try { if (!dbg.isAttached()) dbg.attach('1.3'); } catch (e) { this.session.log('captura.erro', { etapa: 'debugger.attach', erro: e.message }); return; }
    this.attached = true;
    this._onMessage = (_e, method, params) => this._handle(method, params).catch((err) => this.session.logNetwork({ tipo: 'erro', erro: err.message }));
    dbg.on('message', this._onMessage);
    await dbg.sendCommand('Network.enable', { maxResourceBufferSize: 100 * 1024 * 1024, maxTotalBufferSize: 300 * 1024 * 1024 });
    this._onLoad = () => this.snapshot('navegacao').catch(() => {});
    this.wc.on('did-finish-load', this._onLoad);
    this._onDownload = (_e, item) => this._download(item);
    this.wc.session.on('will-download', this._onDownload);
  }

  detach() {
    if (!this.attached) return;
    try { this.wc.debugger.off('message', this._onMessage); this.wc.debugger.detach(); } catch { /* ignore */ }
    this.wc.off('did-finish-load', this._onLoad);
    this.wc.session.off('will-download', this._onDownload);
    this.attached = false;
  }

  async _handle(method, p) {
    const s = this.session;
    if (method === 'Network.requestWillBeSent') {
      s.logNetwork({ tipo: 'requisicao', id: p.requestId, url: p.request.url, metodo: p.request.method, tipo_recurso: p.type, iniciador: p.initiator?.type });
      try {
        const u = new URL(p.request.url);
        if (/^https?:$/.test(u.protocol) && !this.hosts.has(u.hostname)) {
          this.hosts.add(u.hostname);
          dns.lookup(u.hostname, { all: true }).then((r) => s.log('dns', { host: u.hostname, ips: r.map((x) => x.address) })).catch(() => {});
        }
      } catch { /* url inválida */ }
    } else if (method === 'Network.responseReceived') {
      const r = p.response;
      this.responses.set(p.requestId, { url: r.url, mimeType: r.mimeType, status: r.status, type: p.type });
      s.logNetwork({ tipo: 'resposta', id: p.requestId, url: r.url, status: r.status, mime: r.mimeType, ip_remoto: r.remoteIPAddress, porta: r.remotePort, protocolo: r.protocol,
        tls: r.securityDetails ? { protocolo: r.securityDetails.protocol, cifra: r.securityDetails.cipher, sujeito: r.securityDetails.subjectName, emissor: r.securityDetails.issuer, valido_de: r.securityDetails.validFrom, valido_ate: r.securityDetails.validTo, san: r.securityDetails.sanList } : null,
        cabecalhos: pickHeaders(r.headers) });
      if (r.securityDetails) {
        try {
          const origin = new URL(r.url).origin;
          if (!this.origins.has(origin)) { this.origins.add(origin); await this._certificate(origin, r.securityDetails); }
        } catch { /* ignore */ }
      }
    } else if (method === 'Network.loadingFinished') {
      const meta = this.responses.get(p.requestId);
      this.responses.delete(p.requestId);
      if (!meta || !this.config.captura.salvar_midias_rede) return;
      if (!MEDIA_RE.test(meta.mimeType || '') || meta.status >= 400) return;
      if (/^(data|blob):/i.test(meta.url)) return; // já faz parte da página salva (MHTML)
      const maxBytes = (this.config.captura.tamanho_max_midia_mb || 50) * 1024 * 1024;
      if (p.encodedDataLength > maxBytes) return;
      try {
        const body = await this.wc.debugger.sendCommand('Network.getResponseBody', { requestId: p.requestId });
        const buf = Buffer.from(body.body, body.base64Encoded ? 'base64' : 'utf8');
        if (buf.length < 64) return; // ícones/pixels de rastreio
        const h = hashBuffer(buf);
        if (this.savedMedia.has(h)) return;
        this.savedMedia.add(h);
        const name = fileNameFor(meta.url, meta.mimeType);
        s.saveArtifact('midia', name, buf, { url: meta.url, content_type: meta.mimeType, tamanho: buf.length });
      } catch { /* corpo indisponível (stream/blob) - a gravação de vídeo cobre */ }
    }
  }

  async _certificate(origin, sec) {
    try {
      const r = await this.wc.debugger.sendCommand('Network.getCertificate', { origin });
      const pems = [];
      let leaf = null;
      for (const b64 of r.tableNames || []) {
        const cert = forge.pki.certificateFromAsn1(forge.asn1.fromDer(forge.util.decode64(b64)));
        if (!leaf) leaf = cert;
        pems.push(forge.pki.certificateToPem(cert));
      }
      const host = new URL(origin).hostname;
      if (pems.length) fs.writeFileSync(path.join(this.session.dir, 'rede', `cert-${host}.pem`), pems.join(''));
      const d = leaf ? describeCert(leaf) : {};
      this.session.log('certificado.tls', { host, origem: origin, protocolo: sec.protocol, cifra: sec.cipher, subject: d.subject || sec.subjectName, issuer: d.issuer || sec.issuer, serial: d.serial, valido_de: d.valido_de, valido_ate: d.valido_ate, sha256: d.sha256, cadeia: pems.length, arquivo: pems.length ? `rede/cert-${host}.pem` : null });
    } catch (e) {
      this.session.log('certificado.tls', { origem: origin, protocolo: sec.protocol, subject: sec.subjectName, issuer: sec.issuer, erro: e.message });
    }
  }

  /** Screenshot + MHTML da página atual. */
  async snapshot(motivo = 'manual') {
    const s = this.session;
    const url = this.wc.getURL();
    const title = this.wc.getTitle();
    if (!url || url === 'about:blank') return null;
    if (motivo === 'navegacao') s.addUrl(url, title);
    let shot = null;
    if (motivo !== 'navegacao' || this.config.captura.screenshot_em_navegacao) {
      const img = await this.wc.capturePage();
      shot = s.saveArtifact('captura', `${hostOf(url)}.png`, img.toPNG(), { url, titulo: title, motivo, dimensoes: img.getSize() });
    }
    if (motivo !== 'navegacao' || this.config.captura.salvar_pagina_mhtml) {
      const tmp = path.join(s.dir, 'paginas', `.tmp-${Date.now()}.mhtml`);
      try {
        await this.wc.savePage(tmp, 'MHTML');
        const buf = fs.readFileSync(tmp);
        fs.unlinkSync(tmp);
        s.saveArtifact('pagina', `${hostOf(url)}.mhtml`, buf, { url, titulo: title, motivo });
      } catch (e) { s.log('captura.erro', { etapa: 'savePage', url, erro: e.message }); }
    }
    return shot;
  }

  _download(item) {
    const s = this.session;
    const tmp = path.join(s.dir, 'anexos', `.dl-${Date.now()}-${item.getFilename()}`);
    item.setSavePath(tmp);
    item.once('done', (_e, state) => {
      if (state !== 'completed') { s.log('download', { url: item.getURL(), arquivo: item.getFilename(), estado: state }); return; }
      try {
        const buf = fs.readFileSync(tmp); fs.unlinkSync(tmp);
        s.saveArtifact('anexo', item.getFilename(), buf, { url: item.getURL(), content_type: item.getMimeType(), origem: 'download' });
      } catch (e) { s.log('download', { url: item.getURL(), erro: e.message }); }
    });
  }
}

function pickHeaders(h = {}) {
  const keep = ['content-type', 'content-length', 'server', 'date', 'last-modified', 'etag', 'cache-control', 'content-disposition', 'x-request-id', 'cf-ray', 'via'];
  const out = {};
  for (const [k, v] of Object.entries(h)) if (keep.includes(k.toLowerCase())) out[k.toLowerCase()] = String(v).slice(0, 300);
  return out;
}
function hostOf(url) {
  try { const u = new URL(url); if (u.protocol === 'file:') return 'arquivo-local-' + (u.pathname.split('/').pop() || 'pagina').replace(/[^a-z0-9.-]/gi, '_').slice(0, 60); return u.hostname.replace(/[^a-z0-9.-]/gi, '_') || 'pagina'; } catch { return 'pagina'; }
}
function fileNameFor(url, mime) {
  let base = 'midia';
  try { base = decodeURIComponent(new URL(url).pathname.split('/').filter(Boolean).pop() || 'midia'); } catch { /* */ }
  base = base.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 80);
  const ext = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif', 'video/mp4': 'mp4', 'video/webm': 'webm', 'audio/mpeg': 'mp3', 'audio/ogg': 'ogg', 'audio/mp4': 'm4a', 'audio/wav': 'wav' }[(mime || '').toLowerCase().split(';')[0]];
  if (ext && !base.toLowerCase().endsWith('.' + ext)) base += '.' + ext;
  return base;
}

module.exports = { TabCapture };
