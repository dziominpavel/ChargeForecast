# Tasks

## 1. Модель состояния слежения

- [x] 1.1 Добавить `tracking/TrackingHealth.kt`: `TrackingStatus`, `TrackingFailure`, `TrackingHealth.isOperational` и чистую классификацию отказа (`SecurityException` → `NO_PERMISSION`, остальное → `BLOCKED_BY_OS`); проверить `TrackingHealthTest` на переходы и классификацию.
- [x] 1.2 Добавить `tracking/TrackingHealthStore.kt` на `MutableStateFlow` с методами `markStarting`, `markRunning`, `markStopped`, `markBlocked`, `markNoPermission`; проверить `TrackingHealthStoreTest`, что ошибка не перезаписывается нейтральным `STOPPED`, а `STARTING` не сбрасывает уже подтверждённый `RUNNING`.
- [x] 1.3 Создать store в `ChargeForecastApplication` до монитора и отдать сервису/экрану; проверить `.\gradlew.bat assembleDebug` и отсутствие второго экземпляра состояния.

## 2. Health и качество данных в общем состоянии

- [x] 2.1 Добавить в `SessionState` поля `tracking` и `dataQuality` с безопасными дефолтами; разделить `ChargeSessionMonitor` на `engineState` (батарея + тикер) и внешний `state` (копирование health/quality), чтобы смена статуса не вызывала повторный `SessionEngine.update()`; проверить `ChargeSessionMonitorTest`: health доходит до состояния, возраст сессии и сводка не меняются от смены статуса.
- [x] 2.2 Заменить `MutableSharedFlow(replay = 0)` на буферизованный `Channel` с `receiveAsFlow()`; проверить тестом, что ошибка, отправленная до подписки, доходит до первого подписчика и не переигрывается второму.
- [x] 2.3 Перевести `WidgetText.model` на `state.tracking`, обновить `BatteryWidgetProvider`/`BatteryWidgetUpdater` и убрать `getRunningServices()`; проверить `WidgetTextTest` и `assembleDebug`.

## 3. Жизненный цикл сервиса и рестарт

- [x] 3.1 Обновить `ChargeForecastService`: `start()` помечает `STARTING` и ловит отказ; `onCreate()` оборачивает `startForeground()` в `try/catch`, при ошибке пишет статус и вызывает `stopSelf()`; `onStartCommand()` не возвращает `START_STICKY` после неудачного foreground; `onDestroy()` помечает `STOPPED` только для успешно запущенного сервиса; проверить сборку и unit-тест классификации.
- [x] 3.2 Переименовать `BootCompletedReceiver` в `TrackingRestartReceiver`, добавить обработку `ACTION_MY_PACKAGE_REPLACED` рядом с `ACTION_BOOT_COMPLETED`; обновить manifest и проверить `.\gradlew.bat assembleDebug` + `lintDebug`.
- [ ] 3.3 Проверить, что смена `TrackingHealth` доходит до виджета через `Application`-коллектор при старте и остановке сервиса; проверить `WidgetTextTest` и вручную на устройстве (п. 7.4).

## 4. Качество датчиков батареи

- [x] 4.1 Добавить `battery/BatteryDataQuality.kt` и чистый `BatterySensorTracker`: `MISSING` только после трёх подряд пустых чтений, `AVAILABLE` сразу при валидном значении; проверить `BatterySensorTrackerTest` на порог, восстановление и независимость тока/счётчика.
- [x] 4.2 Подключить трекер к `BatteryMonitor`, обновлять `dataQuality` после каждого чтения, заменить немые `catch` на `Log.w`; передать `dataQuality` в монитор; проверить `.\gradlew.bat test` и `assembleDebug`.
- [ ] 4.3 Показать на экране пояснение fallback: вынести выбор текста в чистую функцию, добавить строки для «нет тока», «нет счётчика» и «нет обоих», вывести одну строку в режимах зарядки и разряда; проверить unit-тестом выбора текста и визуально на устройстве/эмуляции.

## 5. Статус на экране и действия

- [ ] 5.1 Добавить `SessionViewModel.restartTracking()` и компактный `TrackingStatusBar` на токенах темы: `STARTING`/`RUNNING` без действия, `STOPPED`/`BLOCKED_BY_OS` с «Перезапустить», `NO_PERMISSION` с переходом в системные настройки; подключить как `bottomBar` только для экрана сессии; проверить `assembleDebug` и визуально все пять состояний.
- [x] 5.2 Проверить доступность и стиль: статус читается TalkBack как текст, декоративная точка без `contentDescription`, нет inline-цветов и `dp`, подсказка `BLOCKED_BY_OS` не обрезается при шрифте 1.5×; проверить `lintDebug`.
- [ ] 5.3 Обновить `MainActivity` под тип `Flow` из ViewModel и убедиться, что snackbar показывает разовую ошибку один раз, а статус слежения не дублируется snackbar'ом; проверить сборку и вручную.

## 6. Документация и changelog

- [x] 6.1 Добавить в `CHANGELOG.md` секцию `## [Unreleased]`: пользовательский блок (жирные категории, буллеты ≤140 символов) и инженерные детали change-id `tracking-health`; проверить `py scripts/check-version.py` — exit 0 без бампа версии.
- [x] 6.2 Обновить `README.md` и `AGENTS.md`: слежение восстанавливается после обновления, статус виден на экране, виджет использует общее health-состояние; проверить, что открытые пункты про EMUI и device-проверки не выданы за закрытые.

## 7. Интеграционная проверка

- [x] 7.1 `.\gradlew.bat test` — все JVM-тесты зелёные, включая новые `TrackingHealthStoreTest`, `BatterySensorTrackerTest` и обновлённые monitor/widget-тесты.
- [x] 7.2 `.\gradlew.bat lintDebug` и `.\gradlew.bat assembleDebug` — без новых ошибок.
- [x] 7.3 На устройстве: `adb install -r` при подключённом кабеле → сервис поднимается без открытия приложения, сессия продолжается; при отключённом кабеле → сервис готов поймать следующую вставку.
- [ ] 7.4 На устройстве: остановить сервис (`adb shell am stopservice ...`) → открыть приложение → видна строка «Слежение остановлено», «Перезапустить» возвращает `RUNNING`; виджет не показывает застывший отсчёт.
- [x] 7.5 На устройстве: проверить строку статуса при работающем слежении, в простое и после перезагрузки; виджет обновляется при старте/остановке сервиса. Проверены `RUNNING`, простой и ребут, виджет при старте/зарядке/разряде; остановка сервиса через `am stop-service` на EMUI заблокирована — остаётся ручной сценарий п. 7.4.
- [ ] 7.6 На устройстве или эмуляции: проверить fallback-пояснение при отсутствии тока/счётчика (или подменой `dataQuality`), исчезновение пояснения после восстановления и отсутствие повторяющихся snackbar.
- [x] 7.7 Регрессия: экран зарядки, разряда и виджет визуально не изменились, кроме новой строки статуса; отсчёт и оценка разряда работают как раньше. Проверено на Huawei MNA-L29; выявлен отдельный артефакт «0 мА» — follow-up.

## Workflow follow-up

- После реализации и проверки на устройстве архивировать change штатным процессом OpenSpec.
- Синхронизировать delta-спеки с основными при архивации; проверить `openspec validate tracking-health --strict`.
