#!/usr/bin/env bash
# Executa os testes instrumentados (app/src/androidTest) num emulador/aparelho onde
# o Sentinel já é Device Owner. Não usa connectedAndroidTest porque o Gradle tenta
# DESINSTALAR o app antes de instalar (um Device Owner não pode ser desinstalado:
# DELETE_FAILED_DEVICE_POLICY_MANAGER). Aqui usamos `adb install -r` (atualização).
# Uso: tools/emulator/instrumented-test.sh <dir-projeto> <dir-saida>
set -uo pipefail
PROJ="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.shieldpericias.sentinel
cd "$PROJ"
./gradlew --no-daemon -q :app:assembleDebug :app:assembleDebugAndroidTest || { echo "[itest] FALHA: build dos APKs"; exit 1; }
adb install -r -g app/build/outputs/apk/debug/app-debug.apk || { echo "[itest] FALHA: install app"; exit 1; }
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || { echo "[itest] FALHA: install test apk"; exit 1; }
adb logcat -c
adb shell am instrument -w -e debug false "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' | tee "$OUT/androidTest-instrument.txt"
adb logcat -d > "$OUT/androidTest-logcat.txt" 2>/dev/null || true
if grep -qE "^OK \([0-9]+ tests?\)" "$OUT/androidTest-instrument.txt"; then
  echo "[itest] resultado: $(grep -E '^OK \(' "$OUT/androidTest-instrument.txt")"; exit 0
fi
echo "[itest] FALHA: $(grep -E 'FAILURES!!!|Tests run:|INSTRUMENTATION_STATUS: stack|Process crashed|INSTRUMENTATION_RESULT' "$OUT/androidTest-instrument.txt" | head -5)"
exit 1
