'use strict';
/**
 * Banco de dados local do aplicativo.
 *
 * Guarda o registro de cada sessão (com o hash raiz gerado) e o log de eventos
 * encadeado por hash (cada evento carrega o hash do anterior). Usa SQLite nativo
 * do Node (node:sqlite) quando disponível; caso contrário, recorre a um arquivo
 * JSONL com a mesma semântica, para que o app nunca fique sem log.
 */
const fs = require('node:fs');
const path = require('node:path');
const { hashString } = require('./hashing');

let sqlite = null;
try { sqlite = require('node:sqlite'); } catch { sqlite = null; }

const GENESIS = '0'.repeat(64);

function eventHash(ev) {
  // Campos canônicos, em ordem fixa, para que o hash seja reproduzível por qualquer verificador.
  return hashString(JSON.stringify([ev.session_id, ev.seq, ev.ts, ev.type, ev.data, ev.prev_hash]));
}

class SqliteBackend {
  constructor(file) {
    this.db = new sqlite.DatabaseSync(file);
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        started_at TEXT NOT NULL,
        ended_at TEXT,
        status TEXT NOT NULL,
        operator TEXT,
        dir_path TEXT,
        root_hash TEXT,
        manifest_sha256 TEXT,
        signature_sha256 TEXT,
        report_sha256 TEXT,
        timestamp_info TEXT,
        registry_receipt TEXT,
        extra TEXT
      );
      CREATE TABLE IF NOT EXISTS events (
        session_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        ts TEXT NOT NULL,
        type TEXT NOT NULL,
        data TEXT,
        prev_hash TEXT NOT NULL,
        hash TEXT NOT NULL,
        PRIMARY KEY (session_id, seq)
      );
    `);
  }
  insertSession(s) {
    this.db.prepare(`INSERT INTO sessions (id, started_at, status, operator, dir_path, extra) VALUES (?, ?, ?, ?, ?, ?)`)
      .run(s.id, s.started_at, s.status, JSON.stringify(s.operator || null), s.dir_path, JSON.stringify(s.extra || null));
  }
  updateSession(id, fields) {
    const keys = Object.keys(fields);
    if (!keys.length) return;
    const set = keys.map((k) => `${k} = ?`).join(', ');
    const vals = keys.map((k) => (typeof fields[k] === 'object' && fields[k] !== null ? JSON.stringify(fields[k]) : fields[k]));
    this.db.prepare(`UPDATE sessions SET ${set} WHERE id = ?`).run(...vals, id);
  }
  getSession(id) {
    const row = this.db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(id);
    return row ? parseSessionRow(row) : null;
  }
  listSessions() {
    return this.db.prepare(`SELECT * FROM sessions ORDER BY started_at DESC`).all().map(parseSessionRow);
  }
  lastEvent(sessionId) {
    return this.db.prepare(`SELECT * FROM events WHERE session_id = ? ORDER BY seq DESC LIMIT 1`).get(sessionId) || null;
  }
  insertEvent(ev) {
    this.db.prepare(`INSERT INTO events (session_id, seq, ts, type, data, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?)`)
      .run(ev.session_id, ev.seq, ev.ts, ev.type, ev.data, ev.prev_hash, ev.hash);
  }
  listEvents(sessionId) {
    return this.db.prepare(`SELECT * FROM events WHERE session_id = ? ORDER BY seq`).all(sessionId);
  }
  close() { this.db.close(); }
}

function parseSessionRow(row) {
  const out = { ...row };
  for (const k of ['operator', 'timestamp_info', 'registry_receipt', 'extra']) {
    if (typeof out[k] === 'string') { try { out[k] = JSON.parse(out[k]); } catch { /* mantém string */ } }
  }
  return out;
}

/** Fallback sem SQLite: um arquivo JSONL append-only por tabela. */
class JsonlBackend {
  constructor(file) {
    this.sessFile = file + '.sessions.jsonl';
    this.evFile = file + '.events.jsonl';
    fs.mkdirSync(path.dirname(file), { recursive: true });
    for (const f of [this.sessFile, this.evFile]) if (!fs.existsSync(f)) fs.writeFileSync(f, '');
  }
  _read(f) { return fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)); }
  _append(f, obj) { fs.appendFileSync(f, JSON.stringify(obj) + '\n'); }
  insertSession(s) { this._append(this.sessFile, { op: 'insert', ...s, operator: s.operator || null, extra: s.extra || null }); }
  updateSession(id, fields) { this._append(this.sessFile, { op: 'update', id, ...fields }); }
  _sessions() {
    const map = new Map();
    for (const r of this._read(this.sessFile)) {
      const { op, ...rest } = r;
      if (op === 'insert') map.set(rest.id, rest);
      else if (map.has(rest.id)) Object.assign(map.get(rest.id), rest);
    }
    return map;
  }
  getSession(id) { return this._sessions().get(id) || null; }
  listSessions() { return [...this._sessions().values()].sort((a, b) => b.started_at.localeCompare(a.started_at)); }
  listEvents(sessionId) { return this._read(this.evFile).filter((e) => e.session_id === sessionId).sort((a, b) => a.seq - b.seq); }
  lastEvent(sessionId) { const l = this.listEvents(sessionId); return l.length ? l[l.length - 1] : null; }
  insertEvent(ev) { this._append(this.evFile, ev); }
  close() {}
}

class AppDatabase {
  /**
   * @param {string} file caminho do arquivo .db (ex.: <userData>/webnotary.db)
   * @param {{forceJsonl?: boolean}} [opts]
   */
  constructor(file, opts = {}) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    this.backend = sqlite && !opts.forceJsonl ? new SqliteBackend(file) : new JsonlBackend(file);
    this.kind = sqlite && !opts.forceJsonl ? 'sqlite' : 'jsonl';
  }

  createSession({ id, operator, dirPath, extra }) {
    const s = { id, started_at: new Date().toISOString(), status: 'aberta', operator, dir_path: dirPath, extra };
    this.backend.insertSession(s);
    return s;
  }

  /** Registra um evento no log encadeado da sessão e devolve o registro completo (com hash). */
  logEvent(sessionId, type, data = {}) {
    const last = this.backend.lastEvent(sessionId);
    const ev = {
      session_id: sessionId,
      seq: last ? last.seq + 1 : 1,
      ts: new Date().toISOString(),
      type,
      data: JSON.stringify(data),
      prev_hash: last ? last.hash : GENESIS,
    };
    ev.hash = eventHash(ev);
    this.backend.insertEvent(ev);
    return ev;
  }

  listEvents(sessionId) { return this.backend.listEvents(sessionId); }

  /** Sela a sessão com o hash raiz e os metadados finais. */
  sealSession(sessionId, { rootHash, manifestSha256, signatureSha256, reportSha256, timestampInfo, registryReceipt }) {
    this.backend.updateSession(sessionId, {
      ended_at: new Date().toISOString(),
      status: 'selada',
      root_hash: rootHash,
      manifest_sha256: manifestSha256 || null,
      signature_sha256: signatureSha256 || null,
      report_sha256: reportSha256 || null,
      timestamp_info: timestampInfo || null,
      registry_receipt: registryReceipt || null,
    });
  }

  updateSession(sessionId, fields) { this.backend.updateSession(sessionId, fields); }
  getSession(id) { return this.backend.getSession(id); }
  listSessions() { return this.backend.listSessions(); }
  close() { this.backend.close(); }
}

/** Verifica a integridade da cadeia de eventos (hash encadeado). */
function verifyEventChain(events) {
  let prev = GENESIS;
  for (let i = 0; i < events.length; i++) {
    const ev = events[i];
    if (ev.seq !== i + 1) return { ok: false, at: i, reason: `sequência quebrada em ${ev.seq}` };
    if (ev.prev_hash !== prev) return { ok: false, at: i, reason: `prev_hash divergente no evento ${ev.seq}` };
    const h = eventHash(ev);
    if (h !== ev.hash) return { ok: false, at: i, reason: `hash divergente no evento ${ev.seq}` };
    prev = ev.hash;
  }
  return { ok: true, count: events.length, lastHash: prev };
}

module.exports = { AppDatabase, verifyEventChain, eventHash, GENESIS, hasSqlite: !!sqlite };
