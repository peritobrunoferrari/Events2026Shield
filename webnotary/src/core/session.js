'use strict';
/**
 * Orquestrador de uma sessão de registro. Independente do Electron: recebe
 * buffers/arquivos, grava tudo na pasta da sessão, registra eventos no banco
 * (log encadeado) e, no encerramento, gera hashes, manifesto, assinaturas,
 * carimbo do tempo, relatório PDF assinado e registro remoto.
 *
 * Estrutura da pasta da sessão:
 *   gravacao.webm            vídeo real da sessão (tela + áudio)
 *   capturas/                screenshots PNG
 *   paginas/                 páginas salvas (MHTML)
 *   midia/                   imagens/vídeos/áudios capturados da rede
 *   anexos/                  arquivos anexados pelo operador
 *   rede/trafego.jsonl       requisições/respostas (cabeçalhos, IPs, TLS)
 *   eventos.jsonl            log de eventos exportado do banco (cadeia de hashes)
 *   manifesto.json/.txt      lista de artefatos + hashes + hash raiz
 *   manifesto.json.p7s       assinatura CAdES destacada do manifesto (+ carimbo)
 *   carimbo-manifesto.tsr    token RFC 3161 (quando obtido)
 *   relatorio.pdf            ata (não assinada)
 *   relatorio-assinado.pdf   ata assinada PAdES (automática)
 *   recibo-registro.json     recibo do servidor de verificação
 *   VERIFICACAO.txt          instruções e hashes finais
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { hashDirectory, hashBuffer, hashFile } = require('./hashing');
const { buildManifest, serializeManifest, manifestToText, manifestHash } = require('./manifest');
const { signCms } = require('./sign/pkcs7');
const { requestTimestamp } = require('./sign/timestamp');
const { generateReportPdf } = require('./report/pdf');
const { signReportPdf } = require('./report/sign-pdf');
const { registerSession } = require('./registry-client');
const { OUTPUT_FILES } = require('./verify');

function newSessionId() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}-${crypto.randomBytes(3).toString('hex')}`;
}

function safeName(name) {
  return String(name).replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 120) || 'arquivo';
}

class RecordingSession {
  /**
   * @param {object} o
   * @param {string} o.baseDir pasta onde as sessões são criadas
   * @param {import('./db').AppDatabase} o.db
   * @param {object} [o.operator] { nome, documento, registro, email }
   * @param {string} [o.purpose]
   * @param {object} [o.environment]
   * @param {string} [o.appVersion]
   */
  constructor(o) {
    this.baseDir = o.baseDir;
    this.db = o.db;
    this.operator = o.operator || null;
    this.purpose = o.purpose || null;
    this.environment = o.environment || null;
    this.appVersion = o.appVersion || null;
    this.id = o.id || newSessionId();
    this.dir = path.join(this.baseDir, this.id);
    this.urls = [];
    this.notes = [];
    this.started = false;
    this.finalized = false;
    this.counters = { captura: 0, pagina: 0, midia: 0, anexo: 0 };
    this.video = null;
  }

  start() {
    for (const sub of ['capturas', 'paginas', 'midia', 'anexos', 'rede']) fs.mkdirSync(path.join(this.dir, sub), { recursive: true });
    this.record = this.db.createSession({ id: this.id, operator: this.operator, dirPath: this.dir, extra: { finalidade: this.purpose, ambiente: this.environment } });
    this.evFile = path.join(this.dir, 'eventos.jsonl');
    fs.writeFileSync(this.evFile, '');
    this.netFile = path.join(this.dir, 'rede', 'trafego.jsonl');
    fs.writeFileSync(this.netFile, '');
    this.started = true;
    this.log('sessao.inicio', { finalidade: this.purpose, operador: this.operator, ambiente: this.environment });
    return this;
  }

  /** Registra evento no banco e espelha no eventos.jsonl (resiliência a queda). */
  log(type, data = {}) {
    if (!this.started || this.finalized) throw new Error('sessão não está aberta');
    const ev = this.db.logEvent(this.id, type, data);
    fs.appendFileSync(this.evFile, JSON.stringify(ev) + '\n');
    return ev;
  }

  /** Tráfego de rede vai para arquivo próprio (volume alto) e um resumo para o log. */
  logNetwork(entry) {
    fs.appendFileSync(this.netFile, JSON.stringify({ ts: new Date().toISOString(), ...entry }) + '\n');
  }

  addUrl(url, title) {
    if (!this.urls.includes(url)) this.urls.push(url);
    return this.log('navegacao', { url, titulo: title || null });
  }

  addNote(text) { this.notes.push(text); return this.log('nota', { texto: text }); }

  /** Salva um buffer como artefato e registra o evento com o hash. */
  saveArtifact(kind, name, buffer, extra = {}) {
    const sub = { captura: 'capturas', pagina: 'paginas', midia: 'midia', anexo: 'anexos' }[kind] || '';
    this.counters[kind] = (this.counters[kind] || 0) + 1;
    const n = String(this.counters[kind]).padStart(3, '0');
    const file = `${n}-${safeName(name)}`;
    const rel = sub ? `${sub}/${file}` : file;
    fs.writeFileSync(path.join(this.dir, rel), buffer);
    const sha256 = hashBuffer(buffer);
    const evType = { captura: 'captura.tela', pagina: 'captura.pagina', midia: 'captura.midia', anexo: 'anexo' }[kind] || 'artefato';
    this.log(evType, { arquivo: rel, tamanho: buffer.length, sha256, ...extra });
    return { rel, sha256 };
  }

  async attachFile(srcPath, extra = {}) {
    const buf = fs.readFileSync(srcPath);
    return this.saveArtifact('anexo', path.basename(srcPath), buf, { origem: srcPath, ...extra });
  }

  /** Vídeo: gravação em fluxo (os trechos chegam do MediaRecorder e são anexados ao arquivo). */
  startVideo({ fonte, audio } = {}) {
    if (this.video) throw new Error('gravação já em andamento');
    const rel = 'gravacao.webm';
    this.video = { rel, fd: fs.openSync(path.join(this.dir, rel), 'w'), bytes: 0, chunks: 0, startedAt: Date.now() };
    this.log('video.inicio', { arquivo: rel, fonte: fonte || 'tela', audio: audio || 'sistema' });
    return rel;
  }
  appendVideoChunk(buffer) {
    if (!this.video) return;
    fs.writeSync(this.video.fd, buffer);
    this.video.bytes += buffer.length;
    this.video.chunks++;
    if (this.video.chunks % 30 === 0) this.log('video.trecho', { trechos: this.video.chunks, bytes: this.video.bytes });
  }
  stopVideo() {
    if (!this.video) return null;
    fs.closeSync(this.video.fd);
    const v = this.video; this.video = null;
    const duracao_s = Math.round((Date.now() - v.startedAt) / 1000);
    this.log('video.fim', { arquivo: v.rel, tamanho: v.bytes, trechos: v.chunks, duracao_s });
    return { ...v, duracao_s };
  }

  /**
   * Encerra a sessão: hashes, manifesto, assinatura+carimbo, relatório assinado, registro remoto.
   * @param {object} o
   * @param {object} [o.signer] { key, certificate, chain, tipo, descricao } (A1 ou A3)
   * @param {string} [o.tsaUrl] endpoint da ACT
   * @param {{username?:string,password?:string}} [o.tsaAuth]
   * @param {{url:string, token?:string}} [o.registry]
   * @param {string} [o.notes]
   * @param {(msg:string)=>void} [o.onProgress]
   */
  async finalize(o = {}) {
    if (!this.started || this.finalized) throw new Error('sessão não está aberta');
    const progress = o.onProgress || (() => {});
    const warnings = [];
    if (this.video) this.stopVideo();
    const videoPath = path.join(this.dir, 'gravacao.webm');
    if (!fs.existsSync(videoPath) || fs.statSync(videoPath).size === 0) warnings.push('Nenhum vídeo foi gravado nesta sessão (captura de tela indisponível ou permissão negada).');
    this.log('sessao.fim', { observacoes: o.notes || null, urls: this.urls });

    // eventos.jsonl canônico a partir do banco
    progress('Exportando log de eventos do banco...');
    const events = this.db.listEvents(this.id);
    fs.writeFileSync(this.evFile, events.map((e) => JSON.stringify(e)).join('\n') + '\n');
    this.finalized = true;

    // hashes de todos os artefatos
    progress('Calculando hashes dos artefatos...');
    const artifacts = await hashDirectory(this.dir, { ignore: OUTPUT_FILES });

    // manifesto
    progress('Gerando manifesto...');
    const sessionInfo = { id: this.id, started_at: this.record.started_at, ended_at: new Date().toISOString(), operator: this.operator, purpose: this.purpose, notes: o.notes || null, urls: this.urls };
    const manifest = buildManifest({ session: sessionInfo, artifacts, events, environment: this.environment });
    const manifestText = serializeManifest(manifest);
    fs.writeFileSync(path.join(this.dir, 'manifesto.json'), manifestText);
    fs.writeFileSync(path.join(this.dir, 'manifesto.txt'), manifestToText(manifest));
    const mHash = manifestHash(manifestText);

    // assinatura do manifesto + carimbo
    let signatureSha256 = null, timestampInfo = null, signatureInfo = null;
    const tsCallback = o.tsaUrl ? async (sigValue) => {
      try {
        progress('Solicitando carimbo do tempo à ACT...');
        const ts = await requestTimestamp(o.tsaUrl, hashBuffer(sigValue), o.tsaAuth || {});
        timestampInfo = { url: o.tsaUrl, info: ts.info, obtido_em: new Date().toISOString() };
        fs.writeFileSync(path.join(this.dir, 'carimbo-manifesto.tsr'), ts.token);
        return ts.token;
      } catch (e) { warnings.push('Carimbo do tempo não obtido: ' + e.message); return null; }
    } : undefined;

    if (o.signer) {
      progress('Assinando manifesto (CAdES)...');
      try {
        const r = await signCms(Buffer.from(manifestText, 'utf8'), { ...o.signer, detached: true, requestTimestamp: tsCallback });
        fs.writeFileSync(path.join(this.dir, 'manifesto.json.p7s'), r.der);
        signatureSha256 = hashBuffer(r.der);
        signatureInfo = { tipo: o.signer.tipo, descricao: o.signer.descricao };
      } catch (e) { warnings.push('Falha ao assinar manifesto: ' + e.message); }
    } else if (o.tsaUrl) {
      // Sem certificado: carimba diretamente o hash do manifesto.
      try {
        progress('Solicitando carimbo do tempo do manifesto...');
        const ts = await requestTimestamp(o.tsaUrl, mHash, o.tsaAuth || {});
        timestampInfo = { url: o.tsaUrl, info: ts.info, sobre: 'sha256(manifesto.json)', obtido_em: new Date().toISOString() };
        fs.writeFileSync(path.join(this.dir, 'carimbo-manifesto.tsr'), ts.token);
      } catch (e) { warnings.push('Carimbo do tempo não obtido: ' + e.message); }
    }

    // registro remoto (apenas hashes)
    let registryReceipt = null;
    if (o.registry && o.registry.url) {
      progress('Registrando hash raiz no servidor de verificação...');
      try {
        registryReceipt = await registerSession(o.registry.url, {
          session_id: this.id, root_hash: manifest.hash_raiz.valor, manifest_sha256: mHash, signature_sha256: signatureSha256,
          operator: this.operator?.nome || null, timestamp_gen_time: timestampInfo?.info?.genTime || null,
        }, { token: o.registry.token });
        fs.writeFileSync(path.join(this.dir, 'recibo-registro.json'), JSON.stringify(registryReceipt, null, 2) + '\n');
      } catch (e) { warnings.push('Registro remoto falhou: ' + e.message); }
    }

    // relatório PDF + assinatura automática
    progress('Gerando relatório PDF...');
    const pdf = await generateReportPdf({ manifest, events, signatureInfo, timestampInfo, registryReceipt, environment: this.environment, appVersion: this.appVersion });
    fs.writeFileSync(path.join(this.dir, 'relatorio.pdf'), pdf);
    let reportSha256 = hashBuffer(pdf);
    let signedReport = null;
    if (o.signer) {
      progress('Assinando relatório PDF (PAdES)...');
      try {
        const r = await signReportPdf(pdf, o.signer, { requestTimestamp: o.tsaUrl ? async (sig) => {
          try { const ts = await requestTimestamp(o.tsaUrl, hashBuffer(sig), o.tsaAuth || {}); return ts.token; } catch { return null; }
        } : undefined });
        signedReport = 'relatorio-assinado.pdf';
        fs.writeFileSync(path.join(this.dir, signedReport), r.pdf);
        reportSha256 = hashBuffer(r.pdf);
      } catch (e) { warnings.push('Falha ao assinar relatório: ' + e.message); }
    }

    // arquivo de verificação legível
    const ver = [
      'WEBNOTARY - VERIFICAÇÃO DA SESSÃO ' + this.id,
      '',
      `Hash raiz (SHA-256):          ${manifest.hash_raiz.valor}`,
      `SHA-256 manifesto.json:       ${mHash}`,
      signatureSha256 ? `SHA-256 manifesto.json.p7s:   ${signatureSha256}` : 'Assinatura do manifesto:      (não assinada)',
      `SHA-256 ${signedReport || 'relatorio.pdf'}: ${reportSha256}`,
      timestampInfo ? `Carimbo do tempo:             ${timestampInfo.info.genTime} (${timestampInfo.url})` : 'Carimbo do tempo:             (não obtido)',
      registryReceipt ? `Registro remoto:              ${registryReceipt.verify_url} em ${registryReceipt.registered_at}` : 'Registro remoto:              (não realizado)',
      '',
      'Para verificar: webnotary-verify "<esta pasta>" [--servidor URL]',
      'Ou manualmente: sha256sum de cada artefato x manifesto.json; openssl cms -verify -binary -inform DER -in manifesto.json.p7s -content manifesto.json',
      warnings.length ? '' : null, ...warnings.map((w) => 'AVISO: ' + w),
    ].filter((l) => l !== null).join('\n') + '\n';
    fs.writeFileSync(path.join(this.dir, 'VERIFICACAO.txt'), ver);

    this.db.sealSession(this.id, { rootHash: manifest.hash_raiz.valor, manifestSha256: mHash, signatureSha256, reportSha256, timestampInfo, registryReceipt });
    progress('Sessão selada.');
    return { id: this.id, dir: this.dir, rootHash: manifest.hash_raiz.valor, manifestSha256: mHash, signatureSha256, reportSha256, signedReport, timestampInfo, registryReceipt, warnings, artifacts: artifacts.length, events: events.length };
  }
}

module.exports = { RecordingSession, newSessionId, safeName };
