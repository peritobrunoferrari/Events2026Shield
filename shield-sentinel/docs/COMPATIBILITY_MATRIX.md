# Matriz de compatibilidade

Estado: **parcialmente validada em emulador** (CI, imagens AOSP x86_64 API 34 e 36: instalação, `dpm set-device-owner`, 12 telas sem crash, serviço de guarda em primeiro plano, auditoria). Nenhuma linha foi executada em aparelho real ainda (ver TEST_PLAN.md). Colunas: ✔ esperado por API pública · ◐ depende do OEM/usuário · ✖ indisponível · ? a validar.

| Recurso | Android 12 (31) | 13 (33) | 14 (34) | 15 (35) | 16 (36) | 17 (37) | Pixel | Samsung | Motorola |
|---|---|---|---|---|---|---|---|---|---|
| Device Owner via ADB/QR | ✔ | ✔ | ✔ emulador | ✔ | ✔ emulador | ✔ | ✔ | ✔ (Knox pode exigir fluxo próprio) ? | ✔ ? |
| Proteção Avançada (camada A) | ✖ | ✖ | ✖ | ✖ | ✔ | ✔ | ✔ | ◐ (OEM) ? | ◐ ? |
| Sinalização USB (camada B) | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✔ esperado (Pixel suporta) ? | ◐ ? | ◐ ? |
| DISALLOW_USB_FILE_TRANSFER | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| reboot() | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| wipeDevice() | ✖ (usa wipeData) | ✖ (wipeData) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Chave auth-bound (prova de desbloqueio) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ (StrongBox) | ✔ (TEE/Knox) ? | ✔ (TEE) ? |
| FGS specialUse | ✖ (tipo não exigido; usa FGS simples) | ✖ idem | ✔ | ✔ | ✔ | ✔ (restrições extras) | ✔ | ◐ (gerenciador de bateria agressivo) ? | ◐ ? |
| Alarmes exatos | ◐ permissão | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ |
| Quick Settings Tile | ✔ | ✔ | ✔ (PendingIntent) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Direct Boot (LOCKED_BOOT_COMPLETED) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |

Notas por fabricante (a confirmar):
- **Samsung**: Knox pode sobrepor políticas USB; verificar conflito via Watchdog (`Aplicada por outro administrador/sistema`); otimização de bateria pode matar o serviço de guarda — usar isenção.
- **Motorola**: gerenciamento de energia pode atrasar alarmes inexatos; conceder alarme exato.
- **Pixel (homologação inicial)**: aparelho de referência; validar Android 16 e 17.
