#!/usr/bin/env bash
# Smoke test em emulador (CI). NÃO executa wipe nem reboot.
# Uso: tools/emulator/smoke-test.sh <apk> <dir-saida>
set -uo pipefail
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.shieldpericias.sentinel
ADMIN="$PKG/$PKG.deviceowner.SentinelDeviceAdminReceiver"
FAIL=0
log() { echo "[smoke] $*"; }
fail() { log "FALHA: $*"; FAIL=1; }

adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.release | sed 's/^/[smoke] Android /'
adb shell getprop ro.build.version.sdk | sed 's/^/[smoke] API /'

log "instalando APK"
adb install -r -g "$APK" || fail "install"

log "provisionando Device Owner via dpm"
DPM_OUT=$(adb shell dpm set-device-owner "$ADMIN" 2>&1 | tr -d '\r'); echo "$DPM_OUT" | tee "$OUT/dpm-set-device-owner.txt"
echo "$DPM_OUT" | grep -q "Success" || fail "dpm set-device-owner"

log "definindo PIN de bloqueio (necessário para a prova de desbloqueio)"
adb shell locksettings set-pin 1234 2>&1 | tr -d '\r' | tee "$OUT/locksettings.txt"

adb logcat -c
screens="dashboard mdm usb reboot watchdog hardening antitheft emergency duress audit diagnostics about"
i=0
for r in $screens; do
  i=$((i+1)); n=$(printf "%02d" $i)
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --es sentinel.route "$r" --ez sentinel.debug.allowCapture true >/dev/null 2>&1 || fail "am start $r"
  sleep 4
  adb exec-out screencap -p > "$OUT/$n-$r.png" || fail "screencap $r"
  if adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime: .*$PKG|Process $PKG .*has died" | grep -q .; then
    fail "crash na tela $r"
    adb logcat -d > "$OUT/logcat-crash-$r.txt"
    adb logcat -c
  fi
  log "tela $r capturada"
done

log "estado do serviço de guarda e do DPC"
adb shell dumpsys activity services $PKG 2>/dev/null | grep -E "SentinelGuardService|isForeground" | head -5 | tee "$OUT/guard-service.txt"
adb shell dumpsys device_policy 2>/dev/null > "$OUT/dumpsys-device_policy.txt" || true
grep -E "Device Owner|$PKG|mDeviceOwner" "$OUT/dumpsys-device_policy.txt" | head -10
adb shell dumpsys activity broadcasts 2>/dev/null | grep -c "$PKG" | sed 's/^/[smoke] broadcasts registrados: /'

log "ciclo bloquear/desbloquear para exercitar UnlockVerifier"
adb shell input keyevent 26; sleep 2            # tela desliga
adb shell input keyevent 26; sleep 1; adb shell input keyevent 82; sleep 1
adb shell input text 1234; adb shell input keyevent 66; sleep 5
adb logcat -d > "$OUT/logcat-final.txt"
adb shell "run-as $PKG cat files/../no_backup/audit.jsonl" 2>/dev/null > "$OUT/audit.jsonl" || adb shell "run-as $PKG sh -c 'cat /data/user/0/$PKG/no_backup/audit.jsonl'" 2>/dev/null > "$OUT/audit.jsonl" || true
if [ -s "$OUT/audit.jsonl" ]; then
  log "eventos de auditoria: $(wc -l < "$OUT/audit.jsonl")"
  grep -oE '"t":"[a-z_]+"' "$OUT/audit.jsonl" | sort | uniq -c | sed 's/^/[smoke]   /'
else
  log "auditoria não lida (run-as indisponível ou arquivo vazio)"
fi

if grep -qE "FATAL EXCEPTION" "$OUT/logcat-final.txt"; then fail "exceção fatal no logcat final"; fi
log "resultado: $([ $FAIL -eq 0 ] && echo OK || echo FALHOU)"
exit $FAIL
