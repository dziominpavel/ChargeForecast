#!/system/bin/sh
# Диагностика батареи Huawei P60 Pro (запуск через adb shell).
# Дамп:   adb shell sh /data/local/tmp/battery_diag.sh            > diag.txt
# Наблюдение 90 сек во время зарядки:
#         adb shell sh /data/local/tmp/battery_diag.sh watch       > watch.txt

echo "===== POWER SUPPLIES ====="
ls -la /sys/class/power_supply/

echo
echo "===== BATTERY FILES ====="
ls -la /sys/class/power_supply/battery/ 2>/dev/null

echo
echo "===== BATTERY VALUES ====="
for f in \
    status capacity current_now current_avg voltage_now charge_counter \
    power_now temp batt_soc batt_current_now batt_current \
    batt_current_ua_now batt_current_ua_avg input_current_now \
    input_current_limit chg_current chg_current_now charge_type \
    charging_enabled
do
    p="/sys/class/power_supply/battery/$f"
    if [ -e "$p" ]; then
        echo -n "$f = "
        cat "$p" 2>/dev/null
    fi
done

echo
echo "===== HW POWER TREE (только список, не читаем) ====="
find /sys/class/hw_power -type f 2>/dev/null

echo
echo "===== CURRENT-RELATED NODES ====="
find /sys/class/power_supply /sys/class/hw_power \
    -type f 2>/dev/null |
    grep -Ei 'current|curr|amp|power|charge|soc|volt'

echo
echo "===== DUMPSYS BATTERY ====="
dumpsys battery

echo
echo "===== SELinux-КОНТЕКСТЫ battery ====="
ls -lZ /sys/class/power_supply/battery/ 2>/dev/null

echo
echo "===== КТО Я ====="
id

if [ "$1" = "watch" ]; then
    echo
    echo "===== WATCH: 90 секунд, каждую секунду ====="
    i=0
    while [ $i -lt 90 ]; do
        date
        for f in status capacity current_now current_avg voltage_now charge_counter temp; do
            p="/sys/class/power_supply/battery/$f"
            if [ -e "$p" ]; then
                echo -n "$f = "
                cat "$p" 2>/dev/null
            fi
        done
        echo "---"
        sleep 1
        i=$((i + 1))
    done
fi
