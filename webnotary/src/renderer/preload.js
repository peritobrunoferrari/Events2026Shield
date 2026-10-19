'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('wn', {
  nav: (acao, url) => ipcRenderer.invoke('nav', acao, url),
  onNavState: (cb) => ipcRenderer.on('nav:state', (_e, s) => cb(s)),
  onStatus: (cb) => ipcRenderer.on('status', (_e, s) => cb(s)),
  onProgress: (cb) => ipcRenderer.on('progress', (_e, m) => cb(m)),
  panel: (aberto) => ipcRenderer.invoke('ui:panel', aberto),

  configGet: () => ipcRenderer.invoke('config:get'),
  configSet: (patch) => ipcRenderer.invoke('config:set', patch),
  escolherArquivo: (opts) => ipcRenderer.invoke('dialog:arquivo', opts),
  escolherPasta: () => ipcRenderer.invoke('dialog:pasta'),
  testarCertificado: (segredo) => ipcRenderer.invoke('cert:testar', segredo),

  sessaoIniciar: (dados) => ipcRenderer.invoke('sessao:iniciar', dados),
  sessaoEncerrar: (dados) => ipcRenderer.invoke('sessao:encerrar', dados),
  sessaoNota: (texto) => ipcRenderer.invoke('sessao:nota', texto),
  sessaoCapturar: () => ipcRenderer.invoke('sessao:capturar'),
  sessaoAnexar: () => ipcRenderer.invoke('sessao:anexar'),
  sessaoEstado: () => ipcRenderer.invoke('sessao:estado'),
  sessoesListar: () => ipcRenderer.invoke('sessoes:listar'),
  sessaoAbrirPasta: (dir) => ipcRenderer.invoke('sessao:abrir-pasta', dir),
  sessaoVerificar: (dir) => ipcRenderer.invoke('sessao:verificar', dir),

  videoIniciado: (info) => ipcRenderer.invoke('video:iniciado', info),
  videoTrecho: (buf) => ipcRenderer.invoke('video:trecho', buf),
  videoParado: () => ipcRenderer.invoke('video:parado'),
  onVideoComando: (cb) => ipcRenderer.on('video:comando', (_e, cmd) => cb(cmd)),
});
