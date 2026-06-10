"""Busca e parsing de feeds RSS/Atom usando apenas a biblioteca padrão.

Suporta RSS 2.0 (<item>) e Atom (<entry>). Faz cache em memória com TTL
e busca os feeds em paralelo para reduzir o tempo de resposta.
"""

from __future__ import annotations

import re
import time
import html
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from xml.etree import ElementTree as ET

USER_AGENT = "Events2026Shield/1.0 (+monitor de noticias do setor)"
HTTP_TIMEOUT = 12  # segundos por feed
CACHE_TTL = 600    # 10 minutos

# (chave_cache) -> (timestamp, lista_de_itens)
_cache: dict[str, tuple[float, list[dict]]] = {}

# Namespaces comuns
_ATOM = "{http://www.w3.org/2005/Atom}"


def _strip_html(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    return re.sub(r"\s+", " ", text).strip()


def _parse_date(raw: str | None) -> datetime | None:
    if not raw:
        return None
    raw = raw.strip()
    # RFC822 (RSS): "Wed, 10 Jun 2026 12:00:00 GMT"
    try:
        dt = parsedate_to_datetime(raw)
        if dt is not None:
            return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except (TypeError, ValueError, IndexError):
        pass
    # ISO 8601 (Atom): "2026-06-10T12:00:00Z"
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def _tag(elem: ET.Element, *names: str) -> str | None:
    """Retorna o texto da primeira tag encontrada (com ou sem namespace)."""
    for name in names:
        child = elem.find(name)
        if child is not None and child.text:
            return child.text
    return None


def _parse_feed(xml_bytes: bytes, source_name: str, sector: str) -> list[dict]:
    items: list[dict] = []
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError:
        return items

    # RSS 2.0: channel/item
    rss_items = root.findall(".//item")
    if rss_items:
        for it in rss_items:
            link = _tag(it, "link") or ""
            items.append(_make_item(
                title=_tag(it, "title"),
                link=link.strip(),
                summary=_tag(it, "description"),
                date_raw=_tag(it, "pubDate") or _tag(it, "{http://purl.org/dc/elements/1.1/}date"),
                source=source_name,
                sector=sector,
            ))
        return items

    # Atom: entry
    for it in root.findall(f"{_ATOM}entry"):
        link = ""
        link_el = it.find(f"{_ATOM}link")
        if link_el is not None:
            link = link_el.get("href", "")
        items.append(_make_item(
            title=_tag(it, f"{_ATOM}title"),
            link=link.strip(),
            summary=_tag(it, f"{_ATOM}summary") or _tag(it, f"{_ATOM}content"),
            date_raw=_tag(it, f"{_ATOM}published") or _tag(it, f"{_ATOM}updated"),
            source=source_name,
            sector=sector,
        ))
    return items


def _make_item(title, link, summary, date_raw, source, sector) -> dict:
    dt = _parse_date(date_raw)
    return {
        "titulo": _strip_html(title or "(sem título)"),
        "link": link,
        "resumo": _strip_html(summary or "")[:280],
        "data": dt.isoformat() if dt else None,
        "data_ts": dt.timestamp() if dt else 0,
        "fonte": source,
        "setor": sector,
    }


def _fetch_one(feed: dict, sector: str) -> dict:
    """Busca um feed. Retorna dict com itens ou erro."""
    url = feed["url"]
    name = feed.get("name", url)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            data = resp.read()
        items = _parse_feed(data, name, sector)
        return {"fonte": name, "ok": True, "itens": items, "erro": None}
    except Exception as exc:  # noqa: BLE001 - queremos degradar sem quebrar
        return {"fonte": name, "ok": False, "itens": [], "erro": str(exc)}


def coletar_noticias(feeds_config: dict, setores: list[str] | None = None,
                     force: bool = False) -> dict:
    """Coleta notícias dos setores indicados (ou todos).

    Retorna {"itens": [...], "fontes": [{fonte, ok, erro, quantidade}]}.
    """
    if setores is None:
        setores = list(feeds_config.keys())

    cache_key = ",".join(sorted(setores))
    now = time.time()
    if not force and cache_key in _cache:
        ts, cached = _cache[cache_key]
        if now - ts < CACHE_TTL:
            return cached

    tarefas = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {}
        for setor in setores:
            cfg = feeds_config.get(setor)
            if not cfg:
                continue
            for feed in cfg.get("feeds", []):
                futures[pool.submit(_fetch_one, feed, setor)] = feed
        for fut in as_completed(futures):
            tarefas.append(fut.result())

    todos_itens: list[dict] = []
    fontes_status: list[dict] = []
    for r in tarefas:
        todos_itens.extend(r["itens"])
        fontes_status.append({
            "fonte": r["fonte"],
            "ok": r["ok"],
            "erro": r["erro"],
            "quantidade": len(r["itens"]),
        })

    # mais recentes primeiro
    todos_itens.sort(key=lambda x: x["data_ts"], reverse=True)
    resultado = {"itens": todos_itens, "fontes": sorted(fontes_status, key=lambda x: x["fonte"])}
    _cache[cache_key] = (now, resultado)
    return resultado
