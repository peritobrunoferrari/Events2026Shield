# Limitações documentadas

1. **Nível de segurança é síntese, não garantia.** Percentual de políticas ativas; não mede resistência a ferramentas forenses.
2. **Controle USB depende do OEM.** `setUsbDataSignalingEnabled` só funciona onde `canUsbDataSignalingBeDisabled()` é verdadeiro. `DISALLOW_USB_FILE_TRANSFER` bloqueia MTP/PTP, não dados USB em geral. Nada equivale ao controle de hardware do GrapheneOS.
3. **Proteção Avançada é só consulta.** A API pública (API 36) permite ler o estado e receber callbacks; a ativação é do usuário. Em Android 12–15 a camada A é INDISPONÍVEL.
4. **Auto-Reboot não é garantido ao segundo.** Sem `SCHEDULE_EXACT_ALARM` concedida, usa janela de até 10 min; Doze pode adiar; `reboot()` falha durante chamadas (nova tentativa em 10 min). Após reiniciar, só rearma no próximo desbloqueio verificado.
5. **Desbloqueio verificado exige bloqueio seguro e autenticação forte.** Biometria classe 1/2 e "deslizar" não zeram contadores. Sem bloqueio seguro, Auto-Reboot/Auto-Wipe não têm baseline e nunca agem.
6. **Auto-Wipe STRICT pode nunca disparar** se o aparelho ficar desligado (tempo ligado não acumula). É deliberado: sem prova, não apaga. A opção WALL_CLOCK_ONLY é vulnerável a alteração de data.
7. **Profile Owner apaga só o perfil; Device Admin legado não apaga** (targetSdk ≥ 34 lança `IllegalStateException` em `wipeData` do usuário primário). Só Device Owner faz factory reset (`wipeDevice`).
8. **Watchdog não impede a própria remoção.** Quem tem a credencial pode remover o DO; reset via recovery é sempre possível; `setUninstallBlocked` não resiste a reset.
9. **Duress PIN só na UI do Sentinel.** Não há interceptação do lockscreen nativo (nem via Accessibility Service, por desenho). Sem modo de disfarce.
10. **Remote Wipe tem apenas transporte manual.** O protocolo está implementado e testado; push/HTTPS são módulos futuros. A chave privada administrativa deve ser guardada offline (`admin-cli`).
11. **Auditoria em BFU é mínima** (tipo + instante) e só é mesclada após o desbloqueio.
12. **Keystore em software** em aparelhos sem TEE/StrongBox reduz a proteção dos segredos (exibido em Diagnóstico).
13. **Idioma.** Interface em pt-BR; `values-en` cobre apenas rótulos de navegação/ações. Textos longos das telas ainda não estão em recursos.
14. **Homologação em aparelho real pendente.** O CI compila APK debug e release (R8), roda os 24 testes do core-logic, o smoke test em emulador (Android 14 e 16, Device Owner provisionado, 12 telas sem crash) e 13 testes instrumentados com Device Owner real (restrições USB, hardening, emergência, auto-wipe em ensaio, reboot com executor falso, comandos remotos, duress, prova de desbloqueio). Sinalização USB real, reboot real, wipe real e o fluxo de desbloqueio biométrico só podem ser validados em hardware. Samsung/Motorola não validados.
15. **R8 no release** encolhe código e recursos mas mantém os nomes de classes do app (`-keepnames`) para facilitar auditoria do APK; a chave de release ainda não foi gerada (o CI produz APK não assinado).
16. **PolicyUpdateReceiver (API 34+)** registra o resultado assíncrono na auditoria e dispara o Watchdog em caso de conflito; em Android 12/13 esse retorno não existe e o estado é apenas relido periodicamente.
17. **SDK 37 com versão menor.** O pacote `platforms;android-37` não existe; o Compose 1.12 exige compileSdk 37.1 (`compileSdkMinor`). O código usa apenas APIs ≤ 36 (AdvancedProtectionManager é a mais recente); a validação de comportamento em Android 17 depende de aparelho/emulador com a imagem final.
18. **Prova de desbloqueio após troca de credencial**: a chave auth-bound é invalidada e recriada; o desbloqueio seguinte conta normalmente.

19. **Sem perfil de trabalho nem Private Space no modo Device Owner.** O Android bloqueia ambos em aparelho totalmente gerenciado (`DISALLOW_ADD_MANAGED_PROFILE` e `DISALLOW_ADD_PRIVATE_PROFILE` padrão `true` com DO, não alteráveis). Um "cofre" para WhatsApp e apps sensíveis exige o modo Profile Owner, planejado em `docs/ROADMAP.md` item 1, sem `reboot()` nesse modo.
