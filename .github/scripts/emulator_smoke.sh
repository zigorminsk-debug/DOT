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

echo "Проверяем сохранённую амплитуду: выбор пользователя и настройки старой сборки"
# Настройки подменяем в файле приложения. Это возможно в отладочной сборке (run-as), а CI собирает именно её.
seed_prefs() {
    local tmp
    tmp=$(mktemp)
    {
        echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
        echo "<map>"
        printf '%s\n' "$@"
        echo "</map>"
    } > "$tmp"
    adb shell run-as "$PKG" mkdir -p "/data/data/$PKG/shared_prefs" || fail "run-as не работает: нужна отладочная сборка"
    # Файл передаём через stdin: он создаётся от имени приложения, без общих папок телефона
    adb shell run-as "$PKG" sh -c "cat > /data/data/$PKG/shared_prefs/motioncalm.xml" < "$tmp" || fail "не удалось записать настройки приложения"
    rm -f "$tmp"
}

# Ждём, пока в дампе интерфейса появится нужный текст (с повторами, как при поиске переключателя)
wait_for_text() {
    local attempt
    for attempt in 1 2 3 4 5 6 7 8; do
        sleep 2
        adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || continue
        XML=$(adb shell cat /sdcard/window.xml | tr -d '\r')
        grep -qF "text=\"$1\"" <<< "$XML" && return 0
    done
    return 1
}

# Выбор 100%, сделанный в новой сборке (есть версия настроек), при запуске не сбрасывается
adb shell am force-stop "$PKG"
seed_prefs '<int name="amp" value="100" />' '<int name="settings_version" value="2" />'
adb shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1
wait_for_text "Амплитуда движения: 100%" || fail "выбор пользователя 100% сбросился при запуске"

# Настройки старой сборки (100% без версии схемы) становятся 50%
adb shell am force-stop "$PKG"
seed_prefs '<int name="amp" value="100" />'
adb shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1
wait_for_text "Амплитуда движения: 50%" || fail "после обновления со старой сборки амплитуда не стала 50%"
notice "Амплитуда: выбор 100% сохраняется, у настроек старой сборки становится 50%"

echo "Запускаем службу подсказок"
# Служба не экспортируется, поэтому извне её не запустить. Открываем приложение и нажимаем
# переключатель подсказок: так проверяется тот же путь, что и у пользователя.
# Телефон стоит вертикально в покое: опора силы тяжести инициализируется по этим показаниям
emu_accel_rest() {
    adb emu sensor set acceleration "0:9.81:0" > /dev/null 2>&1 || fail "эмулятор не принял показания акселерометра"
}
emu_accel_rest
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
# Текст приложения не должен заходить в полосу у края (16 dp): там точки, и они не должны перекрывать текст
DENSITY=$(adb shell wm density | tr -d '\r' | sed -n 's/.*: *\([0-9][0-9]*\).*/\1/p' | tail -n 1)
GUTTER_PX=$(( 16 * ${DENSITY:-420} / 160 ))
TEXT_NODES=$(grep -o 'text="[^"][^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]' <<< "$XML" | sed 's/^text="\([^"]*\)".*bounds="\[\([0-9]*\),.*/\2 \1/' | sort -n)
MIN_TEXT_X=$(head -n 1 <<< "$TEXT_NODES" | cut -d' ' -f1)
notice "полоса у края: $GUTTER_PX px; самые левые тексты (px и текст): $(head -n 3 <<< "$TEXT_NODES" | tr '\n' ';')"
if [ -n "$MIN_TEXT_X" ] && [ "$MIN_TEXT_X" -lt "$GUTTER_PX" ]; then
    fail "текст приложения заходит в полосу у края: $MIN_TEXT_X px < $GUTTER_PX px"
fi
read -r X1 Y1 X2 Y2 <<< "$(grep -o '[0-9]\+' <<< "$BOUNDS" | tr '\n' ' ')"
adb shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
sleep 8
service_alive || fail "процесс приложения не запустился после включения переключателя"
[ "$(heartbeats | grep -c heartbeat)" -ge 1 ] || fail "служба не пишет heartbeat после включения переключателя"

echo "Проверяем реакцию точек на ускорение: эмулятор задаёт показания акселерометра"
# Телефон стоит вертикально экраном к водителю: x вправо, y вверх, z от экрана к водителю.
# Вперёд — от экрана, потому что экран смотрит на водителя: разгон — это ускорение по оси z со знаком минус.
emu_accel() {
    adb emu sensor set acceleration "$1" > /dev/null 2>&1 || fail "эмулятор не принял показания акселерометра: $1"
}
motion_values() {
    adb logcat -d -s MotionCue:I | grep ' motion ' | sed -n "s/.*$1=\([-+0-9.]*\).*/\1/p"
}

emu_accel "0:9.81:0"
sleep 4
adb logcat -c
emu_accel "0:9.81:-2.5"                       # разгон вперёд
sleep 3
MAX_FWD=$(motion_values fwd | sort -g | tail -n 1)
notice "разгон: вперёд $MAX_FWD м/с²"
awk -v v="$MAX_FWD" 'BEGIN { exit !(v + 0 >= 1.8) }' || fail "при разгоне точки не реагируют вперёд: $MAX_FWD"

emu_accel "0:9.81:0"                          # покой: точки должны вернуться по вперёд
sleep 5
adb logcat -c
sleep 2
LAST_FWD=$(motion_values fwd | tail -n 1)
awk -v v="$LAST_FWD" 'BEGIN { exit !(v + 0 <= 0.5 && v + 0 >= -0.5) }' || fail "в покое вперёд не вернулся к нулю: $LAST_FWD"

adb logcat -c
emu_accel "0:9.81:2.5"                        # торможение
sleep 3
MIN_FWD=$(motion_values fwd | sort -g | head -n 1)
notice "торможение: вперёд $MIN_FWD м/с²"
awk -v v="$MIN_FWD" 'BEGIN { exit !(v + 0 <= -1.8) }' || fail "при торможении точки не реагируют назад: $MIN_FWD"

emu_accel "0:9.81:0"
sleep 5
adb logcat -c
emu_accel "2.5:9.81:0"                        # поворот направо: ускорение вправо по оси x
sleep 3
MAX_LAT=$(motion_values lat | sort -g | tail -n 1)
notice "поворот направо: вбок $MAX_LAT м/с²"
awk -v v="$MAX_LAT" 'BEGIN { exit !(v + 0 >= 1.8) }' || fail "при повороте точки не реагируют вбок: $MAX_LAT"
emu_accel "0:9.81:0"
sleep 4

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
