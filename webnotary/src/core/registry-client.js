'use strict';
/**
 * Cliente do servidor de verificação remoto. Envia APENAS hashes e metadados
 * mínimos (nunca o conteúdo da sessão). O servidor guarda em arquivo de texto e
 * devolve um recibo HMAC com a hora de registro.
 */
async function registerSession(serverUrl, record, { token, timeoutMs = 15000 } = {}) {
  const base = serverUrl.replace(/\/+$/, '');
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${base}/api/register`, { method: 'POST', headers, body: JSON.stringify(record), signal: ctrl.signal });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || `servidor respondeu HTTP ${res.status}`);
    return { server: base, ...body };
  } finally { clearTimeout(t); }
}

async function verifyHash(serverUrl, hash, { timeoutMs = 15000 } = {}) {
  const base = serverUrl.replace(/\/+$/, '');
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${base}/api/verify/${encodeURIComponent(hash)}`, { signal: ctrl.signal });
    if (!res.ok) throw new Error(`servidor respondeu HTTP ${res.status}`);
    return await res.json();
  } finally { clearTimeout(t); }
}

module.exports = { registerSession, verifyHash };
