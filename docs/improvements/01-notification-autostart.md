# Проблема 1: шторка не появляется при подключении кабеля

Ожидание пользователя: воткнул кабель → зарядка пошла → в шторке сразу
информация, без ручного запуска приложения.

Фактическое поведение: уведомление появляется только после того, как
пользователь сам открыл приложение.

---

## 1. Диагноз

### 1.1 Цепочка событий сейчас

```
воткнули кабель
   |
   v
PowerConnectionReceiver.onReceive()              PowerConnectionReceiver.kt:14
   |
   v
ChargeForecastService.start() -> ContextCompat.startForegroundService()
                                                 ChargeForecastService.kt:73-78
   |
   v
БРОСАЕТ ForegroundServiceStartNotAllowedException   (Android 12+, приложение
   |                                                  в фоне)
   v
catch (_: SecurityException)                      PowerConnectionReceiver.kt:19
   |
   +-- НЕ ЛОВИТ: ForegroundServiceStartNotAllowedException наследуется от
   |   IllegalStateException, а не от SecurityException (подтверждено
   |   документацией Android API reference)
   |
   v
исключение улетает из onReceive -> процесс молча завершается в фоне
   |
   v
НИ сервиса, НИ fallback-уведомления (postOpenAppFallback не выполняется,
т.к. catch не сработал).
```

Шторка появляется позже только потому, что при открытии приложения
`MainActivity` вызывает `SessionViewModel.ensureService()`
(SessionViewModel.kt:23-43, MainActivity.kt:98-101) — старт FGS из
foreground легален, поэтому «после реального запуска приложения всё работает».

### 1.2 Два независимых дефекта

**Дефект А (баг):** неправильный класс исключения в catch.
`ForegroundServiceStartNotAllowedException extends ServiceStartNotAllowedException
extends IllegalStateException` — это НЕ SecurityException. Ловить нужно
`Exception` (или `IllegalStateException` + `SecurityException`).

**Дефект Б (платформа):** Android 12+ (API 31) запрещает старт foreground
service из фона. Официальный список исключений
(developer.android.com/develop/background-work/services/fgs/restrictions-bg-start):

- переход из видимого состояния (activity на экране);
- приложение может запускать activity из фона (SYSTEM_ALERT_WINDOW и т.п.);
- high-priority FCM;
- действие пользователя по UI-элементу приложения (уведомление, виджет, bubble);
- exact alarm, запрошенный пользователем;
- текущая клавиатура (IME);
- геозоны / activity recognition;
- receiver на `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`;
- receiver на `TIMEZONE_CHANGED` / `TIME_SET` / `LOCALE_CHANGED`;
- NFC `ACTION_TRANSACTION_DETECTED`;
- системные роли (device owner и т.п.).

**События подключения питания в списке НЕТ.** Значит, архитектура
«receiver стартует FGS по кабелю» на Android 12+ принципиально нежизнеспособна.
Так работать не может ни одно приложение без системных привилегий; все живые
мониторы батареи (AccuBattery и аналоги) держат постоянный сервис-монитор.

Дополнительно на Android 12+ система задерживает показ уведомления нового
FGS на ~10 секунд, если у уведомления не выставлено
`setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` — сейчас
в NotificationHelper.buildNotification() это не выставлено
(NotificationHelper.kt:110-123).

---

## 2. Варианты решения

| | A. Постоянный монитор (рекомендую) | B. Best-effort fallback | C. SYSTEM_ALERT_WINDOW |
|---|---|---|---|
| Суть | FGS живёт всегда: стартует из приложения и автоподнимается по `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (легальные исключения). Процесс жив → по кабелю шторка переключается мгновенно | Ловим ЛЮБОЕ исключение старта → кликабельное уведомление «кабель подключён — открыть прогноз»; `JobScheduler.setRequiresCharging(true)` как периодический будильник процесса | Разрешение «поверх других окон» даёт право стартовать activity из фона → из activity легально стартуем FGS |
| Шторка по кабелю | Мгновенно | После тапа пользователя | Мгновенно |
| Цена | Постоянное свернутое уведомление (IMPORTANCE_MIN в простое) + защита от убийства | Лишний тап; JobScheduler мин. период ~15 мин | Хак: враждебно к пользователю, риск политики Google Play, на ряде прошивок отдельно душится |
| Риски | OEM-оптимизаторы (EMUI/MIUI) душат постоянные сервисы → нужен экран-подсказка | На убитом процессе реакция до 15 мин | Не рекомендуется |

**Решение: A + B вместе.** A — основной путь (процесс почти всегда жив,
шторка мгновенная). B — страховка, когда система/OEM убил процесс:
пользователь получает кликабельное уведомление вместо тишины.
Дефект А (catch) чинится в любом случае — это однострочный баг.

---

## 3. Целевая архитектура

```
+--------------------------- процесс жив (почти всегда) -------------------+
|                                                                          |
|   ChargeMonitorService (FGS, specialUse, START_STICKY)                   |
|   - после запуска приложения / BOOT_COMPLETED / MY_PACKAGE_REPLACED      |
|   - в простое: уведомление-заглушка в канале IMPORTANCE_MIN              |
|     (свернуто, без звука, без бейджа, отдельный канал!)                  |
|   - по POWER_CONNECTED / снимку isPlugged:                               |
|       * переключает уведомление в канал "зарядка" (IMPORTANCE_LOW)       |
|       * FOREGROUND_SERVICE_IMMEDIATE -> шторка видна сразу,              |
|         без 10-секундной задержки Android 12+                            |
|       * запускает секундный конвейер измерений (см. документ 02)         |
|   - по POWER_DISCONNECTED: сводка сессии -> обратно в MIN-режим          |
|                                                                          |
+--------------------------------------------------------------------------+

процесс убит системой/OEM:
   POWER_CONNECTED -> receiver -> try startForegroundService
     |-- успех (редкие прошивки/состояния) -> монитор поднялся
     +-- ЛЮБОЕ исключение ->
           1) NotificationHelper.postOpenAppFallback():
              кликабельное уведомление, тап -> MainActivity ->
              ensureService() поднимает монитор из foreground
           2) JobScheduler (periodic, setRequiresCharging(true)):
              будит процесс раз в ~15 мин, пока кабель воткнут,
              повторяет попытку
```

Почему два канала уведомлений: важность канала нельзя менять после создания.
Постоянное «фоновое» уведомление не должно занимать шторку — ему нужен
IMPORTANCE_MIN; активная сессия — IMPORTANCE_LOW (как сейчас CHANNEL_ID
`charge_forecast`, NotificationHelper.kt:22).

`BOOT_COMPLETED` требует разрешения `RECEIVE_BOOT_COMPLETED` в манифесте.
Старт FGS из BOOT_COMPLETED receiver — официальное исключение (см. 1.2).
Ограничение Android 14+ на типы FGS из BOOT_COMPLETED касается
location/camera/mic — `specialUse` не ограничен, но проверить на устройстве
автора обязательно (чек-лист ниже).

`onTaskRemoved()`: при смахивании приложения из recents многие прошивки
убивают sticky-сервис. Переопределить и планировать перезапуск через
JobScheduler (одноразовый, минимальная задержка).

---

## 4. Пошаговый план

### Шаг 1. Починить catch (блокер, однострочный)
`PowerConnectionReceiver.kt:19`: `catch (_: SecurityException)` →
`catch (_: Exception)` с комментарием: фоновый запрет старта — это
IllegalStateException-иерархия, а не SecurityException. Уже одно это
включает fallback-уведомление (сейчас мёртвый код).

### Шаг 2. Второй канал + IMMEDIATE
- `NotificationHelper`: канал `charge_monitor` (IMPORTANCE_MIN, без бейджа,
  без звука) для простоя; существующий `charge_forecast` (IMPORTANCE_LOW)
  остаётся для сессии.
- `buildNotification()`: добавить
  `setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)`.
- Fallback-уведомление оставить на канале сессии (оно должно быть заметным).

### Шаг 3. Постоянный режим сервиса
- `ChargeForecastService`: убрать `stopSelf()` при `!isPlugged`
  (ChargeForecastService.kt:40-43) — вместо остановки переключаться
  на MIN-уведомление-заглушку и останавливать конвейер измерений.
- `onTaskRemoved()`: перепланирование через JobScheduler.
- Старт из `MainActivity`/`ensureService()` — как сейчас, из foreground.

### Шаг 4. Автоподнятие после перезагрузки/обновления
- Манифест: `RECEIVE_BOOT_COMPLETED`; receiver `BootReceiver`
  (exported=true, intent-filter BOOT_COMPLETED + MY_PACKAGE_REPLACED)
  → `ChargeForecastService.start()`.
- Проверка на Android 14+: specialUse из BOOT_COMPLETED не заблокирован
  (живой тест на устройстве автора).

### Шаг 5. Страховка по кабелю (вариант B)
- `PowerConnectionReceiver`: в catch дополнительно планировать
  `JobInfo` (periodic 15 мин, `setRequiresCharging(true)`), отменять job
  в `onCreate` сервиса и по DISCONNECTED.
- Fallback-уведомление: текст «Кабель подключён — откройте прогноз»,
  autoCancel, тап → MainActivity. Уже реализовано
  (`postOpenAppFallback`, NotificationHelper.kt:127-146) — просто теперь
  реально вызывается.

### Шаг 6. Экран-подсказка для OEM (EMUI у автора)
- В `SessionScreen` при живой зарядке без heartbeat (механика NotifStaleLine
  уже есть, SessionScreen.kt:211-225) — отдельная карточка: «Разрешите
  автозапуск и фоновую работу: Настройки → Приложения → ChargeForecast →
  Батарея → Без ограничений / Автозапуск» + кнопка
  `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)` и, где доступно,
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (отдельным явным действием
  пользователя, не автоматом).

### Шаг 7. Тесты и приёмка
- Unit: выбор канала по состоянию (простой/сессия), формат fallback-текстов,
  логика перепланирования JobScheduler (чистая функция «нужен ли job»).
- Живые сценарии — чек-лист ниже.

---

## 5. Критерии приёмки (живые, на устройстве автора)

1. Холодный старт: приложение ни разу не открывали после перезагрузки →
   воткнуть кабель → шторка с прогнозом ≤ 5 сек (путь BOOT_COMPLETED +
   живой монитор).
2. Процесс убит (`adb shell am kill`): воткнуть кабель → в течение 15 мин
   появляется хотя бы fallback-уведомление; тап → приложение + живая шторка.
3. Свайп из recents во время зарядки → шторка продолжает обновляться.
4. Выткнуть кабель → уведомление сессии исчезает ≤ 30 сек, в шторке
   остаётся только свернутый MIN-монитор (не раздражает).
5. Android 12+: шторка появляется без 10-секундной задержки (IMMEDIATE).
6. Перезагрузка телефона с воткнутым кабелем → шторка поднимается сама.
7. `.​/gradlew.bat assembleDebug` и `.​/gradlew.bat test` зелёные.

---

## 6. Затронутые файлы (план)

| Файл | Изменение |
|---|---|
| `PowerConnectionReceiver.kt` | catch Exception + JobScheduler-страховка |
| `ChargeForecastService.kt` | постоянный режим, MIN-заглушка, onTaskRemoved |
| `notification/NotificationHelper.kt` | канал `charge_monitor`, IMMEDIATE, выбор канала |
| `AndroidManifest.xml` | RECEIVE_BOOT_COMPLETED, BootReceiver |
| новый `BootReceiver.kt` | старт монитора после boot/replace |
| новый `MonitorRestartJob.kt` | JobScheduler (framework, без зависимостей) |
| `ui/SessionScreen.kt` | карточка-подсказка OEM-настроек |
| `res/values/strings.xml` | тексты: заглушка монитора, fallback, подсказка |
| тесты | NotificationHelper/JobScheduler-логика |
