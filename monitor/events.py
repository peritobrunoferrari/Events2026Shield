"""Lógica de eventos: cálculo de proximidade de datas e estado de alerta."""

from __future__ import annotations

from datetime import date, datetime


def _parse_iso_date(s: str | None) -> date | None:
    if not s:
        return None
    try:
        return datetime.fromisoformat(s).date()
    except ValueError:
        return None


def processar_eventos(eventos: list[dict], janela_dias: int,
                      hoje: date | None = None) -> list[dict]:
    """Enriquece cada evento com dias restantes e estado.

    Estados: 'em_andamento', 'alerta' (dentro da janela), 'futuro', 'passado'.
    Retorna apenas eventos que ainda não terminaram, ordenados por data.
    """
    hoje = hoje or date.today()
    saida: list[dict] = []

    for ev in eventos:
        inicio = _parse_iso_date(ev.get("inicio"))
        fim = _parse_iso_date(ev.get("fim")) or inicio
        if inicio is None:
            continue

        dias_para_inicio = (inicio - hoje).days

        if fim and fim < hoje:
            estado = "passado"
        elif inicio <= hoje <= (fim or inicio):
            estado = "em_andamento"
        elif 0 <= dias_para_inicio <= janela_dias:
            estado = "alerta"
        else:
            estado = "futuro"

        if estado == "passado":
            continue

        item = dict(ev)
        item["dias_restantes"] = dias_para_inicio
        item["estado"] = estado
        saida.append(item)

    saida.sort(key=lambda e: e["inicio"])
    return saida
