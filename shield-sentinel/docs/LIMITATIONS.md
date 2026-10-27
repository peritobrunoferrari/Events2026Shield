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
14. **Homologação pendente.** O código foi compilado (core-logic com testes) e checado por tipos contra o framework Android 17, mas **ainda não foi executado em aparelho real**. O módulo `app` (Compose) só é compilado no CI/Android Studio. Samsung/Motorola não validados.
15. **Sem R8/minificação** no release desta fase (facilita auditoria; aumentar hardening de build depois da homologação).
16. **PolicyUpdateReceiver (API 34) não implementado**: resultado assíncrono de políticas em conflito com outro admin não é capturado; o estado é relido pelo Watchdog.
17. **Prova de desbloqueio após troca de credencial**: a chave auth-bound é invalidada e recriada; o desbloqueio seguinte conta normalmente.
