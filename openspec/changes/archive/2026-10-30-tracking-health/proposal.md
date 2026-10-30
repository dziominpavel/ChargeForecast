# Proposal

## Why

Фоновое слежение — фундамент продукта: без него вставка кабеля проходит мимо, а экран и виджет показывают застывшие числа. Сейчас сервис может не подняться после обновления приложения, отказ `startForegroundService()` / `startForeground()` либо роняет процесс, либо остаётся невидимым, а сообщение об ошибке, отправленное до подписки UI, теряется навсегда (`SharedFlow(replay = 0)`). Ошибки чтения датчиков батареи тоже глушатся, поэтому fallback на уровень заряда выглядит как «оценка замерла».

Пользователь не должен гадать, работает ли слежение. Если оно остановлено, экран обязан честно сказать об этом и дать способ перезапустить, а виджет — не показывать устаревшее число как живое.

## What Changes

- Ввести процессную модель `TrackingHealth` со статусами `STARTING`, `RUNNING`, `STOPPED`, `BLOCKED_BY_OS`, `NO_PERMISSION` и причиной отказа; включить её в `SessionState`, чтобы экран и виджет читали один источник.
- Расширить приёмник рестарта: поднимать слежение не только после `BOOT_COMPLETED`, но и после `ACTION_MY_PACKAGE_REPLACED`, без ручного открытия приложения.
- Обернуть `startForeground()` и `startForegroundService()` в обработку отказа: не ронять процесс, различать запрет ОС и отсутствие разрешения, переводить сервис в честный статус.
- Заменить поток ошибок с `replay = 0` на буферизованный канал: ошибка, отправленная до создания UI, доходит до первого подписчика.
- Показывать на главном экране постоянную тонкую строку статуса слежения с действием «Перезапустить» (или переходом в настройки приложения при отсутствии разрешения) и подсказкой про автозапуск/оптимизацию батареи для `BLOCKED_BY_OS`.
- Считать недоступность датчика тока/счётчика заряда по нескольким подряд замерам и объяснять на экране, какой fallback используется, вместо молчаливого скрытия строки.
- Перевести виджет с устаревшего `ActivityManager.getRunningServices()` на `TrackingHealth` из общего состояния; обновлять виджет при старте и остановке сервиса.
- Логировать пойманные исключения в пути батареи и не оставлять «немых» `catch`.

Вне рамок изменения: полноценный экран диагностики слежения и группировка настроек (P1-9), запрос `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, замороженная оценка разряда в простое (P1-5), полная карта источников данных (P2-19). Версия и changelog-релиз не меняются.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `background-tracking`: добавляются восстановление после обновления приложения, обработка отказа запуска foreground service, модель и видимость состояния слежения, доставка ошибок до UI.
- `charge-forecast`: добавляется явное объяснение fallback при недоступности датчика тока или счётчика заряда; статус слежения становится частью состояния экрана.

## Impact

- `service/ChargeForecastService.kt`, `service/BootCompletedReceiver.kt` (переименование в общий приёмник рестарта), `AndroidManifest.xml`.
- `ChargeForecastApplication.kt`, `session/ChargeSessionMonitor.kt`, `session/SessionState.kt` (фактически в `ChargeSessionMonitor.kt`).
- Новые `tracking/TrackingHealth.kt` и `tracking/TrackingHealthStore.kt`.
- `battery/BatteryMonitor.kt`, `battery/BatteryDataQuality.kt`.
- `ui/SessionViewModel.kt`, `MainActivity.kt`, `ui/SessionScreen.kt`, строки `res/values/strings.xml`.
- `widget/BatteryWidgetProvider.kt`, `widget/BatteryWidgetUpdater.kt`, `widget/WidgetText.kt`.
- Тесты: `TrackingHealthStoreTest`, `BatteryDataQualityTest`, обновление `ChargeSessionMonitorTest`, `WidgetTextTest`; changelog `[Unreleased]`.
- Новых зависимостей и разрешений нет; `session_runtime` и backup-политика не меняются.
