'use strict';
/** Configuração persistente do aplicativo (userData/config.json). Senhas/PINs nunca são gravados. */
const fs = require('node:fs');
const path = require('node:path');

const DEFAULTS = {
  operador: { nome: '', documento: '', registro: '', email: '' },
  pasta_sessoes: '',                 // vazio = <Documentos>/WebNotary/sessoes
  pagina_inicial: 'https://web.whatsapp.com',
  certificado: { modo: 'nenhum', pfx: '', pkcs11_lib: '', slot: 0 }, // modo: nenhum | A1 | A3
  carimbo: { url: '', usuario: '', senha: '' },               // ex.: ACT ICP-Brasil contratada
  registro: { url: '', token: '' },                           // servidor de verificação (verify-server.js)
  gravacao: { fonte: 'tela', microfone: false, fps: 15, bitrate_kbps: 2500 },
  captura: { screenshot_em_navegacao: true, salvar_pagina_mhtml: true, salvar_midias_rede: true, tamanho_max_midia_mb: 50 },
  ip_publico_url: 'https://api.ipify.org',
};

function deepMerge(base, extra) {
  const out = { ...base };
  for (const [k, v] of Object.entries(extra || {})) {
    out[k] = v && typeof v === 'object' && !Array.isArray(v) && base[k] && typeof base[k] === 'object' ? deepMerge(base[k], v) : v;
  }
  return out;
}

class Config {
  constructor(file) { this.file = file; this.data = deepMerge(DEFAULTS, this._read()); }
  _read() { try { return JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch { return {}; } }
  get() { return JSON.parse(JSON.stringify(this.data)); }
  set(patch) {
    this.data = deepMerge(this.data, patch);
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify(this.data, null, 2));
    return this.get();
  }
}

module.exports = { Config, DEFAULTS };
