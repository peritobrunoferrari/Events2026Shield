# Guia de recuperação administrativa

Princípio: **toda política do Sentinel tem reversão pelo próprio app**, e o administrador deve manter **um caminho de recuperação independente de USB** antes de ativar o perfil USB Máxima ou `DISALLOW_DEBUGGING_FEATURES`.

## 1. Caminhos de acesso administrativo

| Caminho | Pré-condição | O que permite |
|---|---|---|
| App Sentinel (PIN normal) | aparelho desbloqueado | reverter qualquer política, desarmar emergência, remover Device Owner |
| ADB | depuração USB ativa **e** sinalização USB habilitada **e** `DISALLOW_DEBUGGING_FEATURES` ausente | `dpm remove-active-admin` (APK testOnly), logs |
| Credencial do aparelho + Configurações | sempre | desinstalar o app **se** `setUninstallBlocked` não estiver ativo; reset de fábrica **se** `DISALLOW_FACTORY_RESET` não estiver ativo |
| Recovery / Fastboot | acesso físico | reset de fábrica (apaga tudo; Factory Reset Protection pode exigir a conta Google anterior) |

> Com perfil USB **Máxima**, o ADB por cabo deixa de funcionar. ADB sem fio (Wi-Fi) continua possível **se** as opções do desenvolvedor estiverem acessíveis (não aplique `DISALLOW_DEBUGGING_FEATURES` se depender disso).

## 2. Reverter proteções (ordem recomendada)

1. **Proteção USB › Reverter tudo** — reabilita sinalização USB, remove `DISALLOW_USB_FILE_TRANSFER` e `DISALLOW_MOUNT_PHYSICAL_MEDIA`.
2. **Hardening** — desligue as políticas na ordem inversa da ativação (Bloquear desinstalação → Depuração → Fontes desconhecidas → …).
3. **Antirroubo** — desative o Auto-Wipe e o Remote Wipe; mantenha o ensaio ligado.
4. **Reinicialização Automática** — desative.
5. **Emergência** — desarme.
6. **Duress** — desabilite a política e remova o PIN de emergência.

Cada passo gera eventos `policy_reverted` na **Auditoria**.

## 3. Remover o Sentinel como Device Owner

Tela **MDM Local › Recuperação administrativa › Remover Device Owner** (digitar `REMOVER`). Chama `clearDeviceOwnerApp`. Depois disso o app vira aplicativo comum; as restrições de usuário já removidas não voltam. Para redefinir um DO é necessário aparelho sem contas (ver [PROVISIONING.md](PROVISIONING.md)).

Via ADB (somente APK `testOnly`): `adb shell dpm remove-active-admin com.shieldpericias.sentinel/com.shieldpericias.sentinel.deviceowner.SentinelDeviceAdminReceiver`.

## 4. PIN do app perdido

O PIN protege apenas a interface do Sentinel. Sem o PIN normal, as opções são: (a) **desinstalar** o app pelas Configurações (se `setUninstallBlocked` estiver desligado) — políticas DO permanecem até a remoção do DO, portanto evite bloquear a desinstalação sem ter o PIN documentado; (b) `adb shell pm clear com.shieldpericias.sentinel` (apaga configurações e PINs; DO permanece) quando o ADB estiver disponível; (c) reset de fábrica.

## 5. Falhas específicas

| Sintoma | Causa provável | Recuperação |
|---|---|---|
| Aparelho reinicia sozinho a cada N horas | Auto-Reboot ativo e nenhum desbloqueio verificado (ex.: biometria fraca, sem bloqueio seguro) | desbloqueie com PIN/senha; verifique em **Diagnóstico › Prova de desbloqueio**; se necessário desative o Auto-Reboot |
| Alerta "Perda de proteção detectada" | política removida por outro admin, Proteção Avançada desligada, DO removido | abra o Watchdog, veja o item perdido, reaplique |
| "Integridade das configurações: ERRO" | MAC inválido em Device Protected Storage (restauração, adulteração, chave Keystore recriada) | revise configurações; o Auto-Wipe trata estado inconsistente como **inconclusivo** e não apaga |
| Pendrive/Android Auto não funciona | perfil USB Elevada/Máxima | **Proteção USB › Reverter tudo** |
| Notificação persistente incomoda | serviço de guarda | canal "Monitor de proteção" pode ser silenciado (IMPORTANCE_MIN); não desative o canal se quiser observar desbloqueios |

## 6. Antes de homologar em produção

- Documente o PIN normal e guarde a chave privada administrativa (Remote Wipe) offline.
- Mantenha o **modo de ensaio** do Auto-Wipe ligado até confirmar, em aparelho secundário, que o contador se comporta como esperado por ≥ 2 ciclos de reboot.
- Teste **Reverter tudo** antes de aplicar Máxima.
