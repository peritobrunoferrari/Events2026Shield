#!/usr/bin/env python3
"""Events2026Shield - Dashboard de eventos e notícias do setor.

Backend HTTP em biblioteca padrão (sem dependências). Execute:

    python3 app.py            # http://127.0.0.1:8000
    PORT=9000 python3 app.py  # porta customizada

Setores monitorados: Perícia/Forense Digital, Cibersegurança e Forense+Jurídico.
"""

from __future__ import annotations

import json
import os
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

from monitor.feeds import coletar_noticias
from monitor.events import processar_eventos
from monitor.alerts import marcar_alertas

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
WEB_DIR = BASE_DIR / "web"

PORT = int(os.environ.get("PORT", "8000"))
HOST = os.environ.get("HOST", "127.0.0.1")


def _load_json(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _save_json(path: Path, data: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


class Handler(BaseHTTPRequestHandler):
    server_version = "Events2026Shield/1.0"

    # --- utilidades de resposta ---
    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: Path, content_type: str) -> None:
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            self.send_error(404, "Arquivo não encontrado")
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):  # menos verboso
        return

    # --- rotas ---
    def do_GET(self):
        parsed = urlparse(self.path)
        route = parsed.path
        qs = parse_qs(parsed.query)

        if route in ("/", "/index.html"):
            return self._send_file(WEB_DIR / "index.html", "text/html; charset=utf-8")
        if route == "/style.css":
            return self._send_file(WEB_DIR / "style.css", "text/css; charset=utf-8")
        if route == "/app.js":
            return self._send_file(WEB_DIR / "app.js", "application/javascript; charset=utf-8")

        if route == "/api/noticias":
            return self._api_noticias(qs)
        if route == "/api/eventos":
            return self._api_eventos()
        if route == "/api/alertas":
            return self._send_json(_load_json(DATA_DIR / "alerts.json"))
        if route == "/api/setores":
            feeds = _load_json(DATA_DIR / "feeds.json")
            return self._send_json({
                k: {"label": v.get("label", k), "fontes": len(v.get("feeds", []))}
                for k, v in feeds.items()
            })

        self.send_error(404, "Rota não encontrada")

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != "/api/alertas":
            return self.send_error(404, "Rota não encontrada")
        length = int(self.headers.get("Content-Length", "0"))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return self._send_json({"erro": "JSON inválido"}, status=400)

        cfg = _load_json(DATA_DIR / "alerts.json")
        if "palavras_chave" in body and isinstance(body["palavras_chave"], list):
            cfg["palavras_chave"] = [str(p) for p in body["palavras_chave"]]
        if "janela_evento_dias" in body:
            try:
                cfg["janela_evento_dias"] = max(0, int(body["janela_evento_dias"]))
            except (TypeError, ValueError):
                pass
        _save_json(DATA_DIR / "alerts.json", cfg)
        return self._send_json({"ok": True, "config": cfg})

    # --- handlers de API ---
    def _api_noticias(self, qs: dict):
        feeds_cfg = _load_json(DATA_DIR / "feeds.json")
        alerts_cfg = _load_json(DATA_DIR / "alerts.json")

        setores_param = qs.get("setores", [""])[0]
        setores = [s for s in setores_param.split(",") if s] or None
        force = qs.get("force", ["0"])[0] == "1"

        resultado = coletar_noticias(feeds_cfg, setores, force=force)
        itens, total_alerta = marcar_alertas(
            resultado["itens"], alerts_cfg.get("palavras_chave", [])
        )
        return self._send_json({
            "itens": itens[:200],
            "fontes": resultado["fontes"],
            "total": len(itens),
            "total_alerta": total_alerta,
        })

    def _api_eventos(self):
        eventos_cfg = _load_json(DATA_DIR / "events.json")
        alerts_cfg = _load_json(DATA_DIR / "alerts.json")
        janela = int(alerts_cfg.get("janela_evento_dias", 45))
        eventos = processar_eventos(eventos_cfg.get("eventos", []), janela)
        feeds = _load_json(DATA_DIR / "feeds.json")
        labels = {k: v.get("label", k) for k, v in feeds.items()}
        for ev in eventos:
            ev["setor_label"] = labels.get(ev.get("setor"), ev.get("setor"))
        em_alerta = sum(1 for e in eventos if e["estado"] in ("alerta", "em_andamento"))
        return self._send_json({
            "eventos": eventos,
            "total": len(eventos),
            "em_alerta": em_alerta,
            "hoje": date.today().isoformat(),
            "janela_dias": janela,
        })


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"  Events2026Shield rodando em  http://{HOST}:{PORT}")
    print("  Setores: Perícia/Forense Digital · Cibersegurança · Forense+Jurídico")
    print("  Ctrl+C para encerrar.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n  Encerrando...")
        server.shutdown()


if __name__ == "__main__":
    main()
