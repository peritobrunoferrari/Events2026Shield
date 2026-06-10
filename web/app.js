"use strict";

const estado = {
  setoresAtivos: new Set(),   // vazio = todos
  setores: {},
  somenteAlerta: false,
};

const $ = (id) => document.getElementById(id);

function fmtData(iso) {
  if (!iso) return "sem data";
  const d = new Date(iso);
  if (isNaN(d)) return "sem data";
  return d.toLocaleDateString("pt-BR", { day: "2-digit", month: "short", year: "numeric" });
}

function fmtDiasRestantes(ev) {
  if (ev.estado === "em_andamento") return "ACONTECENDO";
  if (ev.dias_restantes === 0) return "hoje";
  if (ev.dias_restantes === 1) return "amanhã";
  return `em ${ev.dias_restantes} dias`;
}

// ---------- setores / filtros ----------
async function carregarSetores() {
  const resp = await fetch("/api/setores");
  estado.setores = await resp.json();
  const nav = $("filtros-setor");
  nav.innerHTML = "";

  const chipTodos = criarChip("todos", "Todos os setores", true);
  nav.appendChild(chipTodos);

  for (const [chave, info] of Object.entries(estado.setores)) {
    nav.appendChild(criarChip(chave, `${info.label} (${info.fontes})`, false));
  }
}

function criarChip(chave, texto, ativoInicial) {
  const el = document.createElement("span");
  el.className = "chip" + (ativoInicial ? " ativo" : "");
  el.textContent = texto;
  el.dataset.chave = chave;
  el.onclick = () => alternarSetor(chave);
  return el;
}

function alternarSetor(chave) {
  if (chave === "todos") {
    estado.setoresAtivos.clear();
  } else {
    if (estado.setoresAtivos.has(chave)) estado.setoresAtivos.delete(chave);
    else estado.setoresAtivos.add(chave);
  }
  // atualizar visual
  document.querySelectorAll(".chip").forEach((c) => {
    const k = c.dataset.chave;
    if (k === "todos") c.classList.toggle("ativo", estado.setoresAtivos.size === 0);
    else c.classList.toggle("ativo", estado.setoresAtivos.has(k));
  });
  atualizarTudo();
}

// ---------- eventos ----------
async function carregarEventos() {
  const resp = await fetch("/api/eventos");
  const dados = await resp.json();
  $("info-janela").textContent = `· alerta com ${dados.janela_dias} dias de antecedência`;

  let eventos = dados.eventos;
  if (estado.setoresAtivos.size > 0) {
    eventos = eventos.filter((e) => estado.setoresAtivos.has(e.setor));
  }

  $("r-eventos").textContent = eventos.length;
  $("r-eventos-alerta").textContent = eventos.filter(
    (e) => e.estado === "alerta" || e.estado === "em_andamento"
  ).length;

  const cont = $("lista-eventos");
  if (eventos.length === 0) {
    cont.innerHTML = `<p class="muted">Nenhum evento futuro nos setores selecionados.</p>`;
    return;
  }
  cont.innerHTML = "";
  for (const ev of eventos) cont.appendChild(renderEvento(ev));
}

function renderEvento(ev) {
  const div = document.createElement("div");
  const emAlerta = ev.estado === "alerta" || ev.estado === "em_andamento";
  div.className = "item evento" + (emAlerta ? " em-alerta" : "");

  let badgeClasse = "badge-dias";
  if (ev.estado === "em_andamento") badgeClasse += " andamento";
  else if (ev.estado === "alerta") badgeClasse += " alerta";

  const periodo = ev.fim && ev.fim !== ev.inicio
    ? `${fmtData(ev.inicio)} – ${fmtData(ev.fim)}`
    : fmtData(ev.inicio);

  const estimado = ev.status === "estimado"
    ? `<span class="status-estimado" title="Data estimada, confirme na fonte">⚠ data estimada</span>` : "";

  div.innerHTML = `
    <div class="topo-ev">
      <a href="${ev.url}" target="_blank" rel="noopener">${ev.nome}</a>
      <span class="${badgeClasse}">${fmtDiasRestantes(ev)}</span>
    </div>
    <div class="meta">
      <span class="data">📆 ${periodo}</span>
      <span class="tag setor">${ev.setor_label}</span>
      <span class="muted">📍 ${ev.local || "—"}</span>
      ${estimado}
    </div>
    ${ev.notas ? `<div class="resumo">${ev.notas}</div>` : ""}
  `;
  return div;
}

// ---------- notícias ----------
async function carregarNoticias(force = false) {
  $("lista-noticias").innerHTML = `<p class="muted">Buscando feeds…</p>`;
  const setores = [...estado.setoresAtivos].join(",");
  const url = `/api/noticias?setores=${encodeURIComponent(setores)}&force=${force ? 1 : 0}`;
  let dados;
  try {
    const resp = await fetch(url);
    dados = await resp.json();
  } catch (e) {
    $("lista-noticias").innerHTML = `<p class="muted">Erro ao buscar notícias.</p>`;
    return;
  }

  $("r-noticias").textContent = dados.total;
  $("r-noticias-alerta").textContent = dados.total_alerta;
  renderFontes(dados.fontes);

  let itens = dados.itens;
  if (estado.somenteAlerta) itens = itens.filter((i) => i.em_alerta);

  const cont = $("lista-noticias");
  if (itens.length === 0) {
    cont.innerHTML = `<p class="muted">Nenhuma notícia ${estado.somenteAlerta ? "em alerta " : ""}encontrada. (Feeds podem estar bloqueados pela rede.)</p>`;
    return;
  }
  cont.innerHTML = "";
  for (const it of itens.slice(0, 80)) cont.appendChild(renderNoticia(it));
}

function renderFontes(fontes) {
  const cont = $("status-fontes");
  cont.innerHTML = "";
  for (const f of fontes) {
    const pill = document.createElement("span");
    pill.className = "fonte-pill" + (f.ok ? "" : " erro");
    pill.textContent = f.ok ? `${f.fonte} · ${f.quantidade}` : `${f.fonte} · falhou`;
    if (!f.ok && f.erro) pill.title = f.erro;
    cont.appendChild(pill);
  }
}

function renderNoticia(it) {
  const div = document.createElement("div");
  div.className = "item" + (it.em_alerta ? " em-alerta" : "");
  const setorLabel = estado.setores[it.setor]?.label || it.setor;
  const tagsAlerta = (it.alertas || [])
    .map((a) => `<span class="tag alerta">${a}</span>`).join("");
  div.innerHTML = `
    <a href="${it.link}" target="_blank" rel="noopener">${it.titulo}</a>
    <div class="meta">
      <span class="tag fonte">${it.fonte}</span>
      <span class="tag setor">${setorLabel}</span>
      <span class="muted">🕒 ${fmtData(it.data)}</span>
      ${tagsAlerta}
    </div>
    ${it.resumo ? `<div class="resumo">${it.resumo}</div>` : ""}
  `;
  return div;
}

// ---------- config de alertas ----------
async function carregarConfig() {
  const resp = await fetch("/api/alertas");
  const cfg = await resp.json();
  $("kw").value = (cfg.palavras_chave || []).join("\n");
  $("janela").value = cfg.janela_evento_dias ?? 45;
}

async function salvarConfig() {
  const palavras = $("kw").value.split("\n").map((s) => s.trim()).filter(Boolean);
  const janela = parseInt($("janela").value, 10) || 0;
  const resp = await fetch("/api/alertas", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ palavras_chave: palavras, janela_evento_dias: janela }),
  });
  if (resp.ok) {
    $("config-msg").textContent = "✓ salvo — aplicando…";
    await atualizarTudo();
    setTimeout(() => ($("config-msg").textContent = ""), 2500);
  } else {
    $("config-msg").textContent = "Erro ao salvar.";
  }
}

// ---------- orquestração ----------
async function atualizarTudo(force = false) {
  $("ultima-atualizacao").textContent = "atualizando…";
  await Promise.all([carregarEventos(), carregarNoticias(force)]);
  const agora = new Date().toLocaleTimeString("pt-BR");
  $("ultima-atualizacao").textContent = `atualizado às ${agora}`;
}

function init() {
  $("btn-atualizar").onclick = () => atualizarTudo(true);
  $("btn-salvar").onclick = salvarConfig;
  $("somente-alerta").onchange = (e) => {
    estado.somenteAlerta = e.target.checked;
    carregarNoticias(false);
  };

  carregarSetores()
    .then(carregarConfig)
    .then(() => atualizarTudo(false));

  // auto-refresh a cada 10 min
  setInterval(() => atualizarTudo(false), 600000);
}

init();
