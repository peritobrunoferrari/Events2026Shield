#!/usr/bin/env bash
# Inicia o dashboard Events2026Shield (somente Python 3, sem dependências).
set -e
cd "$(dirname "$0")"
PORT="${PORT:-8000}"
echo "Abra http://127.0.0.1:${PORT} no navegador"
exec python3 app.py
