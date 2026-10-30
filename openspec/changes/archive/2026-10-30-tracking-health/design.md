# Design

## Context

Сейчас единственный источник состояния — `ChargeSessionMonitor.state`: его читают экран, виджет и журнал. Сервис не держит второго движка, а лишь будит монитор по событию кабеля. Это свойство нужно сохранить: статус слежения должен стать частью того же состояния, а не отдельным каналом для каждой поверхности.

Текущие ограничения:

- `ChargeForecastService` стартует из `MainActivity.onCreate()` и `BootCompletedReceiver`; обработчика `ACTION_MY_PACKAGE_REPLACED` нет.
- `startForeground()` в `onCreate()` не обёрнут в `try/catch`, а `start()` ловит только `IllegalStateException`.
- `ChargeSessionMonitor.errors` — `MutableSharedFlow(replay = 0)`, поэтому ошибка до подписки UI теряется.
- Виджет определяет живость слежения через deprecated `ActivityManager.getRunningServices()` на каждом тике.
- `BatteryMonitor` глушит исключения чтения тока/счётчика; экран не объясняет fallback.
- `SessionState` собирается в `combine(batterySnapshots, ticker)`; туда же нельзя просто добавить health-поток, иначе каждое изменение статуса повторно вызовет `SessionEngine.update()` с тем же снапшотом.

См. `proposal.md` — Why и What Changes; требования — в delta-спеках `background-tracking` и `charge-forecast`.

## Goals / Non-Goals

**Goals:**

- Сделать состояние слежения наблюдаемым и пригодным для экрана, виджета и будущей диагностики.
- Пережить обновление приложения и отказ платформы без падения и без «немого» простоя.
- Не потерять ошибку, возникшую до подписки UI.
- Убрать deprecated `getRunningServices()` из горячего пути виджета.
- Объяснить пользователю fallback датчиков, не спамя snackbar.

**Non-Goals:**

- Полный экран диагностики слежения, группировка настроек и инструкции по каждому OEM — это P1-9.
- Запрос `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` и автоматическое отключение оптимизации батареи.
- Периодический heartbeat сервиса и проверка свежести снапшота по таймеру: в этой итерации статус меняется по жизненному циклу сервиса.
- Замороженная оценка разряда в простое (P1-5) и полная карта источников данных (P2-19).
- Изменение backup-политики, `SessionRuntimeStore` и версии приложения.

## Decisions

### D1. `TrackingHealth` — процессное состояние, а не событие

Вводится чистый Kotlin-модуль `tracking`:

```kotlin
enum class TrackingStatus { STARTING, RUNNING, STOPPED, BLOCKED_BY_OS, NO_PERMISSION }
enum class TrackingFailure { START_BLOCKED, FOREGROUND_BLOCKED, PERMISSION_MISSING, UNKNOWN }

data class TrackingHealth(
    val status: TrackingStatus = TrackingStatus.STOPPED,
    val failure: TrackingFailure? = null
) {
    val isOperational: Boolean
        get() = status == TrackingStatus.RUNNING || status == TrackingStatus.STARTING
}
```

`TrackingHealthStore` — тонкий holder с `MutableStateFlow<TrackingHealth>` и методами `markStarting()`, `markRunning()`, `markStopped()`, `markBlocked(failure)`, `markNoPermission()`. Он живёт в `ChargeForecastApplication` рядом с монитором.

Почему так:

- статус — это значение, которое должно пережить смену подписчика; событие для этого не подходит;
- store не зависит от Android и тестируется на JVM;
- Application уже владеет процессными зависимостями, поэтому сервис и экран получают один экземпляр.

Альтернативы: static/companion в сервисе (плохо тестируется и переживает пересоздание процесса), `getRunningServices()` (deprecated, binder-вызов, не даёт причину), отдельный broadcast (лишняя церемония и потеря состояния).

Начальное значение — `STOPPED`: сервис ещё не подтвердил работу. `MainActivity` вызывает `ChargeForecastService.start()` до `setContent()`, поэтому к первой композиции store уже в `STARTING`.

### D2. Health входит в `SessionState`, но не перезапускает движок

`SessionState` получает поля `tracking: TrackingHealth = TrackingHealth.running()` и `dataQuality: BatteryDataQuality = BatteryDataQuality.UNKNOWN`. Дефолты сохраняют совместимость с существующими конструкторами в тестах.

`ChargeSessionMonitor` делит поток на два слоя:

1. `engineState = combine(batterySnapshots, ticker) { ... engine.update(...) }` — всё как сейчас, включая запись live-снимка.
2. `state = combine(engineState, trackingHealth, dataQuality) { base, health, quality -> base.copy(...) }`.

Это важно: если добавить health в первый `combine`, каждое изменение статуса вызовет `SessionEngine.update()` с тем же снапшотом и тем же временем. Это может повторно скормить спидометрам один и тот же замер и исказить сессию. Второй слой только копирует поля.

В конструктор `ChargeSessionMonitor` параметры добавляются в конец со значениями по умолчанию (`StateFlow<TrackingHealth>?`, `StateFlow<BatteryDataQuality>?`), поэтому существующие тесты и вызовы не ломаются. В production Application передаёт `trackingHealthStore.health` и `batteryMonitor.dataQuality`.

### D3. Сервис сообщает статус по жизненному циклу

`ChargeForecastService`:

- в `start(context)`: если текущий статус не `RUNNING`, помечает `STARTING`; вызывает `startForegroundService()`; ловит `Exception`; `SecurityException` → `NO_PERMISSION`, остальное → `BLOCKED_BY_OS`; пишет причину в log;
- в `onCreate()`: создаёт канал и сразу вызывает `ServiceCompat.startForeground()` в `try/catch`. Успех → `markRunning()` и регистрация power-receiver. `SecurityException` → `markNoPermission()`, иначе → `markBlocked(FOREGROUND_BLOCKED)`; затем `stopSelf()` и выход без регистрации receiver;
- в `onStartCommand()`: если foreground не поднялся — `stopSelf()` и `START_NOT_STICKY`, чтобы система не перезапускала заведомо падающий сервис; иначе `START_STICKY`;
- в `onDestroy()`: если foreground был поднят, `markStopped()`; после ошибки статус не перезаписывается на нейтральный.

`startForeground()` остаётся первым действием `onCreate()`: платформа отводит около 5 секунд, тяжёлая работа до него недопустима.

Причина отказа классифицируется один раз в чистой функции (например, `TrackingFailure.classify(Throwable)`), чтобы её можно было покрыть JVM-тестом без Android.

### D4. Рестарт после обновления и загрузки — один приёмник

`BootCompletedReceiver` переименовывается в `TrackingRestartReceiver` и обрабатывает `ACTION_BOOT_COMPLETED` и `ACTION_MY_PACKAGE_REPLACED`. Manifest получает второй `<action>`.

Почему переименование: имя `BootCompletedReceiver` перестаёт отражать поведение. Дублировать два почти одинаковых класса ради сохранения имени не стоит.

Запуск FGS из `MY_PACKAGE_REPLACED` может быть запрещён оболочкой. Это не повод падать: `start()` переводит store в `BLOCKED_BY_OS`, а следующее открытие приложения повторит попытку. Если на устройстве автор окажется, что система блокирует всегда, понадобится отдельный follow-up (WorkManager/AlarmManager) — в этот change он не входит.

### D5. Ошибки — буферизованный канал, статус — отдельное состояние

`ChargeSessionMonitor._errors` заменяется на `Channel<String>(capacity = 16, onBufferOverflow = DROP_OLDEST)`, наружу — `receiveAsFlow()`. `reportError()` использует `trySend()`.

Это даёт:

- ошибка, отправленная до подписки, лежит в буфере и доходит до первого подписчика;
- после доставки она не переигрывается новому подписчику;
- при переполнении теряется самая старая ошибка, а не новая.

Статус слежения при этом не дублируется snackbar'ом: постоянная строка на экране — основная поверхность. Snackbar остаётся для разовых ошибок, у которых нет места в статусе.

### D6. Качество датчиков — отдельный трекер с порогом три замера

Вводится чистый `BatteryDataQuality`:

```kotlin
enum class SensorAvailability { UNKNOWN, AVAILABLE, MISSING }

data class BatteryDataQuality(
    val currentSensor: SensorAvailability = SensorAvailability.UNKNOWN,
    val counterSensor: SensorAvailability = SensorAvailability.UNKNOWN
)
```

`BatteryMonitor` держит `MutableStateFlow<BatteryDataQuality>` и обновляет его после каждого сырого чтения через чистый `BatterySensorTracker`:

- валидное значение сразу даёт `AVAILABLE` и сбрасывает серию;
- `null` увеличивает серию; `MISSING` выставляется только после трёх подряд;
- при восстановлении датчика статус сразу возвращается в `AVAILABLE`.

Порог в три замера защищает от одиночного пропуска драйвера. В простое, где опрос остановлен, качество не «стареет»: оно меняется только по новым чтениям, а экран в простое и так показывает оценку разряда.

Все `catch` в пути батареи получают `Log.w(TAG, ...)`; поведение fallback не меняется. Это выполняет правило «не глушить исключения» без превращения каждого сбоя в snackbar.

### D7. Статус на экране — компактная нижняя строка

В `ChargeForecastApp` при показе сессии добавляется `bottomBar` со `TrackingStatusBar`:

- `STARTING` — «Запускаю слежение…», без действия;
- `RUNNING` — «Слежение активно», без действия;
- `STOPPED` — «Слежение остановлено» + «Перезапустить»;
- `BLOCKED_BY_OS` — «Система остановила слежение» + «Перезапустить» + подсказка про автозапуск и оптимизацию батареи;
- `NO_PERMISSION` — «Нет разрешения на фоновую работу» + «Настройки» (системные настройки приложения).

Строка использует только токены темы (`MaterialTheme.colorScheme`, `MaterialTheme.typography`, `ChargeDimens`), без inline-цветов и `dp`. Индикатор — декоративная точка, текст читается TalkBack как обычный текст. На экране настроек строка не показывается, чтобы не мешать нижнему блоку версии; это можно пересмотреть в P1-9.

«Перезапустить» вызывает `SessionViewModel.restartTracking()` → `ChargeForecastService.start(application)`. «Настройки» — Intent `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` с package-URI, без новых разрешений.

Почему нижняя строка, а не баннер сверху: она не конкурирует с главным числом «до 100%», всегда видна и не требует закрытия. Почему не только snackbar: статус должен быть постоянным, а не исчезать через несколько секунд.

### D8. Виджет читает health из общего состояния

`WidgetText.model(state)` больше не принимает boolean `tracking`; режим `NOT_TRACKING` выбирается по `!state.tracking.isOperational`. `BatteryWidgetProvider.isTrackingRunning()` и импорт `ActivityManager` удаляются. `BatteryWidgetUpdater` продолжает получать `SessionState` из Application, поэтому старт и остановка сервиса обновляют виджет через уже существующий поток.

`STARTING` считается operational: иначе при открытии приложения виджет на секунду мигнул бы «слежение остановлено». Если запуск сорвётся, статус сменится на `BLOCKED_BY_OS`/`NO_PERMISSION`, и виджет честно уйдёт в `NOT_TRACKING`.

### D9. Тестовая стратегия

JVM-тесты:

- `TrackingHealthStoreTest` — переходы `STOPPED → STARTING → RUNNING → STOPPED`, блокировки, отсутствие перезаписи ошибки нейтральным `STOPPED`;
- `TrackingFailureTest` — классификация `SecurityException` и `IllegalStateException`;
- `BatterySensorTrackerTest` — порог трёх замеров, восстановление, независимость тока и счётчика;
- `ChargeSessionMonitorTest` — health и quality попадают в `SessionState`; изменение health не сбрасывает сессию и не дублирует сводку; ошибка, отправленная до подписки, доставляется;
- `WidgetTextTest` — `NOT_TRACKING` по health, `STARTING` не показывает остановку, обновлённая сигнатура.

Ручные проверки на устройстве:

- `adb install -r` при подключённом и отключённом кабеле;
- остановка сервиса/запрет фоновой работы → строка статуса и кнопка перезапуска;
- виджет при старте/остановке сервиса;
- экран без датчика тока (если есть устройство) либо эмуляция через отсутствующие extra;
- регрессия: экран и виджет не показывают застывшее число при `NOT_TRACKING`.

## Risks / Trade-offs

- **`MY_PACKAGE_REPLACED` не разрешает FGS на некоторых прошивках** → отказ ловится, статус `BLOCKED_BY_OS` виден, следующее открытие повторяет попытку; если проблема системная — follow-up с WorkManager/AlarmManager.
- **`startForeground()` падает в `onCreate()`** → немедленный `stopSelf()` без тяжёлой работы до вызова; процесс не должен успеть получить ANR. Проверить на устройстве.
- **Кратковременный `STOPPED` при холодном старте** → `start()` синхронно ставит `STARTING` до композиции; `STARTING` считается operational для виджета.
- **Нижняя строка отнимает место у hero-экрана** → компактная высота, только на экране сессии; P1-7 всё равно перестроит иерархию.
- **Ложное `MISSING` из-за серии пустых чтений** → порог три замера и мгновенное восстановление по первому валидному значению.
- **Health остаётся `RUNNING`, если сервис умер без `onDestroy`** → в одном процессе это редкий сценарий; при смерти процесса store создаётся заново в `STOPPED`. Периодический heartbeat сознательно отложен.
- **Буферизованный канал теряет старые ошибки при переполнении** → ёмкость 16 и `DROP_OLDEST`; критичный статус живёт в `TrackingHealth`, а не в канале.

## Migration Plan

Изменение не трогает формат `SessionRuntimeStore`, backup и версию. Достаточно собрать и установить обновление; откат — обычный revert. В `CHANGELOG.md` добавляется пользовательский блок и инженерные детали в `## [Unreleased]`, без бампа версии.
