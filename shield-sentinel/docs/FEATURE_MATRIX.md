# Matriz de recursos e permissões

Legenda de modos: **DO** Device Owner · **PO** Profile Owner · **DA** Device Admin legado · **—** app comum. "API" é o nível mínimo da API usada (minSdk do projeto = 31).

| Recurso | API Android usada | API | DO | PO | DA | — | Estado exibido quando não permitido |
|---|---|---|---|---|---|---|---|
| Detecção de modo administrativo | `isDeviceOwnerApp`, `isProfileOwnerApp`, `isAdminActive` | 21 | ✔ | ✔ | ✔ | ✔ | — |
| Consulta Proteção Avançada (camada A) | `AdvancedProtectionManager.isAdvancedProtectionEnabled` | 36 | ✔ | ✔ | ✔ | ✔ | `Unavailable` < API 36 |
| Sinalização USB (camada B) | `canUsbDataSignalingBeDisabled`, `setUsbDataSignalingEnabled`, `isUsbDataSignalingEnabled` | 31 | ✔ | PO corporativo | ✖ | ✖ | `Unavailable` se aparelho não suporta ou sem DO |
| Transferência de arquivos (camada C) | `addUserRestriction(DISALLOW_USB_FILE_TRANSFER)` | 21 | ✔ | ✔ (perfil) | ✖ | ✖ | `Unavailable` |
| Mídia física/OTG | `DISALLOW_MOUNT_PHYSICAL_MEDIA` | 21 | ✔ | ✔ | ✖ | ✖ | `Unavailable` |
| Auto-Reboot | `DevicePolicyManager.reboot` + `AlarmManager` | 24 | ✔ | ✖ | ✖ | ✖ | `Unavailable` |
| Bloqueio imediato | `lockNow` | 8 | ✔ | ✔ | ✔ | ✖ | `Unsupported` |
| Apagamento do aparelho | `wipeDevice(flags)` (≥34) / `wipeData` (31–33) | 34/8 | ✔ | ✖ | ✖ | ✖ | `Unavailable` |
| Apagamento do perfil | `wipeData(flags, reason)` | 28 | ✖ | ✔ | ✖ | ✖ | `Unavailable` |
| Fontes desconhecidas | `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` | 29 | ✔ | ✖ | ✖ | ✖ | `Unavailable` |
| Depuração | `DISALLOW_DEBUGGING_FEATURES` | 21 | ✔ | ✔ | ✖ | ✖ | `Unavailable` |
| Modo seguro / reset pela UI / novos usuários | `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`, `DISALLOW_ADD_USER` | 21–23 | ✔ | ✖ | ✖ | ✖ | `Unavailable` |
| Data/hora protegidas | `DISALLOW_CONFIG_DATE_TIME` + `setAutoTimeEnabled` | 28/30 | ✔ | ✔ | ✖ | ✖ | `Unavailable` |
| Câmera | `setCameraDisabled` | 14 | ✔ | ✔ | ✔ | ✖ | `Unsupported` |
| Captura de tela (sistema) | `setScreenCaptureDisabled` | 21 | ✔ | ✔ | ✖ | ✖ | `Unavailable` |
| Captura de tela (no app) | `FLAG_SECURE` | 1 | ✔ | ✔ | ✔ | ✔ | — |
| Complexidade de senha | `setRequiredPasswordComplexity` / `getPasswordComplexity` | 31/29 | ✔ | ✔ | ✖ | leitura | `Unavailable` |
| Tempo máximo para bloquear | `setMaximumTimeToLock` | 8 | ✔ | ✔ | ✔ | ✖ | `Unavailable` |
| Agentes de confiança | `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_TRUST_AGENTS)` | 17 | ✔ | ✔ | ✔ | ✖ | `Unavailable` |
| Bloquear desinstalação | `setUninstallBlocked` | 21 | ✔ | ✔ | ✖ | ✖ | `Unavailable` |
| Conceder POST_NOTIFICATIONS a si | `setPermissionGrantState` | 23 | ✔ | ✔ | ✖ | ✖ | `Unsupported` |
| Info de atualização pendente | `getPendingSystemUpdate` | 26 | ✔ | ✔ | ✖ | ✖ | oculto |
| Prova de desbloqueio | Keystore `setUserAuthenticationParameters` | 30 | ✔ | ✔ | ✔ | ✔ | `Unavailable` sem bloqueio seguro |
| Watchdog / alertas | `AlarmManager`, `NotificationManager`, FGS specialUse | 31 | ✔ | ✔ | ✔ | ✔ (só leitura) | — |
| Quick Settings Tile | `TileService` | 24 | ✔ | ✔ | ✔ | ✔ | tile `UNAVAILABLE` quando desarmado |
| Remote Wipe (protocolo) | ECDSA P-256 / Keystore / SecureKv | 31 | ✔ | ✔ (perfil) | ✖ | ✖ | `Rejected(DISABLED)` por padrão |
| Duress PIN | PBKDF2-HMAC-SHA256 + SecureKv | 31 | ✔ | ✔ | parcial (LOCK) | ✖ | — |

## Permissões declaradas (app/src/main/AndroidManifest.xml)

| Permissão | Tipo | Uso | Módulo |
|---|---|---|---|
| `RECEIVE_BOOT_COMPLETED` | normal | reagendar alarmes, observar livro-razão, iniciar serviço de guarda em BFU | watchdog |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | normal | serviço de guarda (observa `USER_PRESENT`) | watchdog |
| `POST_NOTIFICATIONS` | runtime (API 33+) | alertas locais e notificação do serviço | watchdog |
| `SCHEDULE_EXACT_ALARM` | especial (usuário concede) | Auto-Reboot pontual; sem ela usa janela inexata | auto-reboot |
| `QUERY_ADVANCED_PROTECTION_MODE` | normal (API 36) | consultar Proteção Avançada | usb-security |
| `REQUEST_PASSWORD_COMPLEXITY` | normal (API 29) | ler complexidade da credencial atual | device-hardening |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | normal | botão para isentar o serviço de guarda | watchdog |
| `BIND_DEVICE_ADMIN` / `BIND_QUICK_SETTINGS_TILE` | proteção de componente (declaradas nos componentes) | receiver DPC / tile | device-owner, emergency |

**Não declaradas (por desenho):** `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_PHONE_STATE`, `RECEIVE_SMS`, `BIND_ACCESSIBILITY_SERVICE`, qualquer permissão de localização, contatos ou mídia.

## Políticas do DeviceAdminReceiver (`sentinel_device_admin.xml`)

`watch-login`, `force-lock`, `wipe-data`, `limit-password`, `disable-camera`, `disable-keyguard-features`.
