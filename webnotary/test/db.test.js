'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AppDatabase, verifyEventChain, hasSqlite } = require('../src/core/db');

for (const forceJsonl of hasSqlite ? [false, true] : [true]) {
  test(`banco (${forceJsonl ? 'jsonl' : 'sqlite'}): sessões, log encadeado e selagem com hash raiz`, () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wn-db-'));
    const db = new AppDatabase(path.join(dir, 'app.db'), { forceJsonl });
    db.createSession({ id: 'S1', operator: { nome: 'Perito' }, dirPath: '/x' });
    const e1 = db.logEvent('S1', 'sessao.inicio', { a: 1 });
    const e2 = db.logEvent('S1', 'navegacao', { url: 'https://a' });
    assert.strictEqual(e1.seq, 1); assert.strictEqual(e2.seq, 2);
    assert.strictEqual(e2.prev_hash, e1.hash);
    const events = db.listEvents('S1');
    assert.strictEqual(events.length, 2);
    assert.ok(verifyEventChain(events).ok);
    // adulteração de um evento quebra a cadeia
    const tampered = events.map((e) => ({ ...e }));
    tampered[0].data = JSON.stringify({ a: 2 });
    assert.strictEqual(verifyEventChain(tampered).ok, false);
    db.sealSession('S1', { rootHash: 'f'.repeat(64), manifestSha256: 'a'.repeat(64) });
    const s = db.getSession('S1');
    assert.strictEqual(s.status, 'selada');
    assert.strictEqual(s.root_hash, 'f'.repeat(64));
    assert.deepStrictEqual(s.operator, { nome: 'Perito' });
    assert.strictEqual(db.listSessions().length, 1);
    db.close();
  });
}
