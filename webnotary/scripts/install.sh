#!/usr/bin/env bash
# Instalação para desenvolvimento/uso local (Linux/macOS). Requer Node.js 20+ (recomendado 22).
set -euo pipefail
cd "$(dirname "$0")/.."
command -v node >/dev/null || { echo "Instale o Node.js 22 (https://nodejs.org) e rode novamente."; exit 1; }
echo "Instalando dependências (inclui o Chromium embutido do Electron)..."
npm install --no-audit --no-fund
echo
echo "Pronto. Para abrir o WebNotary:   npm start"
echo "Para gerar instalador (.AppImage/.deb ou .dmg):   npm run dist"
echo "Para o servidor de verificação:   npm run server   (ou: PORT=8787 WEBNOTARY_API_TOKEN=... node server/verify-server.js)"
