#!/usr/bin/env bash
# Проверка на эмуляторе Android: служба подсказок продолжает работать после выключения
# и включения экрана. Так проверяется сценарий «разблокировал телефон, подсказки должны ожить».
#
# Использование: emulator_smoke.sh <путь_к_apk>
# Итог печатается аннотациями GitHub (::notice / ::error), их видно на странице запуска и через API.
set -uo pipefail

APK="$1"
PKG="com.example.motioncalm"
SVC="$PKG/.MotionCueService"

notice() {
    echo "::notice title=Эмулятор::$1"
    echo "$1"
}

fail() {
    # Аннотация одной строкой: иначе GitHub покажет только первую строку сообщения
    echo "::error title=Эмулятор::$(printf '%s' "$1" | tr '\n' ' ' | tr -s ' ')"
    echo "ОШИБКА: $1"
    echo "--- последние записи службы:"
    adb logcat -d -s MotionCue:I | tail -n 15
    exit 1
}

# Строки heartbeat, которые служба пишет раз в 5 секунд, пока экран включён
heartbeats() {
    adb logcat -d -s MotionCue:I | grep heartbeat
}

# Значение поля (events=, frames=) из строки heartbeat
field() {
    sed -n "s/.*$1=\([0-9]*\).*/\1/p" <<< "$2" | head -n 1
}

power_state() {
    adb shell dumpsys power | tr -d '\r' | sed -n 's/^ *mWakefulness=//p' | head -n 1
}

service_alive() {
    adb shell pidof "$PKG" | tr -d '\r' | grep -q '[0-9]'
}

echo "Ждём загрузку эмулятора"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
adb shell input keyevent 82 > /dev/null 2>&1      # снимаем экран блокировки, если он есть
adb shell wm dismiss-keyguard > /dev/null 2>&1

echo "Устанавливаем APK"
INSTALL_OUT=$(adb install -r -g "$APK" 2>&1)
echo "$INSTALL_OUT"
grep -q "Success" <<< "$INSTALL_OUT" || fail "APK не установился"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

echo "Запускаем службу подсказок"
# Служба не экспортируется, поэтому извне её не запустить. Открываем приложение и нажимаем
# переключатель подсказок: так проверяется тот же путь, что и у пользователя.
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1
# Холодный запуск на эмуляторе занимает несколько секунд: ищем переключатель с повторами
BOUNDS=""
for attempt in 1 2 3 4 5 6 7 8; do
    sleep 2
    adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || continue
    XML=$(adb shell cat /sdcard/window.xml | tr -d '\r')
    BOUNDS=$(grep -o 'resource-id="com.example.motioncalm:id/switchCue"[^>]*' <<< "$XML" | grep -o 'bounds="[^"]*"' | head -n 1)
    [ -n "$BOUNDS" ] && break
done
if [ -z "$BOUNDS" ]; then
    fail "переключатель подсказок не найден; видимые id: $(grep -o 'resource-id="[^"]*"' <<< "$XML" | head -n 8 | tr '\n' ' ')"
fi
read -r X1 Y1 X2 Y2 <<< "$(grep -o '[0-9]\+' <<< "$BOUNDS" | tr '\n' ' ')"
adb shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
sleep 8
service_alive || fail "процесс приложения не запустился после включения переключателя"
[ "$(heartbeats | grep -c heartbeat)" -ge 1 ] || fail "служба не пишет heartbeat после включения переключателя"

echo "Выключаем экран"
adb shell input keyevent 223                      # SLEEP
sleep 6
STATE=$(power_state)
[ "$STATE" = "Asleep" ] || fail "экран не выключился, состояние: $STATE"
sleep 6
service_alive || fail "служба погибла, пока экран выключен"

echo "Включаем экран, как после разблокировки"
adb logcat -c
adb shell input keyevent 224                      # WAKEUP
sleep 1
adb shell wm dismiss-keyguard > /dev/null 2>&1
sleep 14
STATE=$(power_state)
[ "$STATE" = "Awake" ] || fail "экран не включился, состояние: $STATE"
service_alive || fail "служба погибла после включения экрана"

LOG=$(adb logcat -d -s MotionCue:I)
grep -q "экран включён" <<< "$LOG" || fail "служба не заметила включение экрана"

HB=$(heartbeats)
N=$(grep -c heartbeat <<< "$HB")
[ "$N" -ge 2 ] || fail "после включения экрана мало heartbeat-записей: $N"
FIRST=$(head -n 1 <<< "$HB")
LAST=$(tail -n 1 <<< "$HB")
EV1=$(field events "$FIRST")
EV2=$(field events "$LAST")
FR1=$(field frames "$FIRST")
FR2=$(field frames "$LAST")
notice "После включения экрана: события датчика $EV1 -> $EV2, кадры $FR1 -> $FR2"

grep -q "sensor=on" <<< "$LAST" || fail "датчик не подписан после включения экрана: $LAST"
[ $((EV2 - EV1)) -ge 20 ] || fail "после включения экрана датчик не отдаёт события ($EV1 -> $EV2)"
[ $((FR2 - FR1)) -ge 20 ] || fail "после включения экрана кадры не идут ($FR1 -> $FR2)"

if adb logcat -d -b crash | grep -q "$PKG"; then
    fail "в журнале падений есть записи приложения"
fi

notice "OK: служба пережила выключение экрана и продолжила работу"
exit 0
