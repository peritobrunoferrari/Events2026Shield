# Instalação no Windows (PowerShell). Requer Node.js 20+ (recomendado 22): https://nodejs.org
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Write-Host "Instale o Node.js 22 (https://nodejs.org) e rode novamente."; exit 1 }
Write-Host "Instalando dependências (inclui o Chromium embutido do Electron)..."
npm install --no-audit --no-fund
Write-Host ""
Write-Host "Pronto. Para abrir o WebNotary:          npm start"
Write-Host "Para gerar o instalador Windows (NSIS):  npm run dist:win   (gera dist\WebNotary Setup x.y.z.exe)"
Write-Host "Token A3: instale o driver do fabricante (ex.: SafeNet Authentication Client) e aponte a DLL PKCS#11 em Configurações."
