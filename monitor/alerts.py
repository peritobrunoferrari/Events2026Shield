"""Detecção de alertas em notícias por palavra-chave."""

from __future__ import annotations


def marcar_alertas(itens: list[dict], palavras_chave: list[str]) -> tuple[list[dict], int]:
    """Marca cada notícia com as palavras-chave encontradas (case-insensitive).

    Retorna (itens_marcados, total_em_alerta).
    """
    termos = [p.strip().lower() for p in palavras_chave if p.strip()]
    total = 0
    for item in itens:
        texto = f"{item.get('titulo', '')} {item.get('resumo', '')}".lower()
        encontrados = sorted({t for t in termos if t in texto})
        item["alertas"] = encontrados
        item["em_alerta"] = bool(encontrados)
        if encontrados:
            total += 1
    return itens, total
