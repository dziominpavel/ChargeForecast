# Tasks

## 1. Boot identity и runtime-хранилище

- [x] 1.1 Добавить `BootIdentity` (bootCount, wallClockMs, elapsedRealtimeMs) и Android-провайдер через `Settings.Global.BOOT_COUNT`; проверить `BootIdentityTest`: одна загрузка, разные загрузки, неизвестный bootCount.
- [x] 1.2 Добавить `SessionRuntimeStore` на отдельный `session_runtime` prefs для bootCount, discharge-состояния, active-snapshot и capacity; проверить `SessionRuntimeStoreTest` на round-trip и отсутствие ключей.
- [x] 1.3 Перевести `SessionEngine` на capacity из runtime-хранилища; проверить, что `SessionEngineTest` и `SessionCacheTest` проходят без старых ключей `capacity_uah`.
- [x] 1.4 Добавить runtime-хранилище в `ChargeForecastApplication` рядом с `SessionCache`; проверить сборку `.\gradlew.bat assembleDebug`.

## 2. Окно разряда: ребут и backup

- [x] 2.1 Добавить `bootCount` в `DischargeState`; в `restore(state, currentBootCount)` отбрасывать сэмплы при несовпадении известных bootCount и по консервативной эвристике, если bootCount недоступен; проверить `DischargeEstimatorTest` на restore после ребута.
- [x] 2.2 Ужесточить `prune`: сортировать/валидировать сэмплы, отбрасывать `atMs > nowMs`, не смешивать старые и новые интервалы; проверить тест на неотсортированный вход и «будущую» метку.
- [x] 2.3 Передавать текущий bootCount в оценщик при старте Application; проверить `ChargeSessionMonitorTest`, что после смены загрузки окно пустое и показывается приор.
- [x] 2.4 Добавить сценарий восстановления из backup/другого устройства в `DischargeEstimatorTest`; проверить, что `summary().trackedMs == 0` и оценка не смешивает устройства.

## 3. Активная сессия: снимок и восстановление

- [x] 3.1 Добавить `ActiveSessionSnapshot` (id, bootCount, старт, уровень, тип, CC-накопления, пик, последний уровень, lastUpdatedAt) и nullable `SessionSummary.id`; проверить `SessionCacheTest` на сериализацию и старую сводку без id.
- [x] 3.2 Добавить `CcPhaseAccumulator.snapshot()`/`restore()`; проверить `CcPhaseAccumulatorTest`, что средняя CC-скорость переживает снимок.
- [x] 3.3 Добавить `SessionEngine.snapshotActiveSession()` и `restoreActiveSession(snapshot)`; при восстановлении перечитывать историю/кривую и сбрасывать транзиентные спидометры, EMA, `DisplayFilter`, `CapacityTracker`.
- [x] 3.4 Проверить `SessionEngineTest`: после restore в той же загрузке сохраняются старт, стартовый уровень и CC-средняя; прогноз снова появляется после первого тика.
- [x] 3.5 Проверить `SessionEngineTest`: `restoreActiveSession(snapshot)` отклоняет снимок с другим bootCount и не склеивает сессии; сценарий финализации старой сессии проверяется в 4.2.

## 4. Финализация, запись и проводка

- [x] 4.1 Сделать `SessionCache.finalizeActiveSession(snapshot)` идемпотентным: проверять `loadSummary()?.id`, сохранять сводку и очищать runtime-снимок; проверить `SessionCacheTest` на повторную финализацию без второй сводки.
- [x] 4.2 В `ChargeForecastApplication.onCreate()` восстановить boot identity, discharge и active snapshot до создания монитора; при смене загрузки финализировать старую сессию и стартовать без неё; проверить `ChargeSessionMonitorTest` и сборку.
- [x] 4.3 Добавить троттлинг записи active-snapshot (10–30 с, плюс старт/финиш и границы уровней) через отдельный persister; проверить unit-тестом, что за 60 с пишется не больше трёх снимков.
- [x] 4.4 Обновить `SessionCache`: старые ключи `discharge_*`/`capacity_uah` больше не читаются и не пишутся; проверить `grep` по ключам и `SessionCacheTest`.
- [x] 4.5 Добавить запись в `CHANGELOG.md` → `## [Unreleased]` (пользовательский блок + инженерные детали) без бампа версии; проверить `py scripts/check-version.py` — exit 0.

## 5. Backup и защита от чужих данных

- [x] 5.1 Исключить `session_runtime.xml` и `discharge-journal.txt` в `backup_rules.xml` и `data_extraction_rules.xml`; проверить `.\gradlew.bat assembleDebug` и вручную просмотреть merged backup rules.
- [x] 5.2 Убедиться, что `charge_session_cache` (сводка, история, кривая) остаётся в backup; проверить diff XML и `SessionCacheTest` на отсутствие runtime-ключей в этом файле.
- [x] 5.3 Проверить сценарий «восстановление из backup на другом устройстве»: discharge-окно пустое, активной сессии нет, история/кривая не отравляют оценку.

## 6. Интеграционная проверка

- [x] 6.1 `.\gradlew.bat test` — все JVM-тесты зелёные, включая новые сценарии ребута/force-stop.
- [x] 6.2 `.\gradlew.bat lintDebug` — без новых ошибок; `assembleDebug` собирается.
- [x] 6.3 На устройстве: `adb shell am force-stop` во время зарядки → открыть приложение → сессия продолжается с прежним стартом, сводка не пишется. Проверено на Huawei MNA-L29 (EMUI 12): тот же `sessionId`, старт 53%, сводка не тронута.
- [x] 6.4 На устройстве: `adb reboot` во время зарядки → после загрузки старая сессия закрыта, сводка сохранена один раз, новая сессия стартует; оценка разряда не использует старые сэмплы. Проверено: сервис поднялся сам по `BOOT_COMPLETED`, новая сессия с 55%, окно разряда `0m`; сводка не пишется при наборе <2% — по спецификации.
- [x] 6.5 На устройстве: force-stop, затем отключить кабель, затем открыть приложение → сессия закрыта, сводка сохранена один раз, новая не стартует. Проверено: сессия закрыта, снимок стёрт (после фикса `ActiveSessionPersister.adopt()`); сводка без CC-данных не пишется — по спецификации.
- [x] 6.6 Проверить, что экран и виджет не изменились визуально; в простое оценка разряда по-прежнему показывает приор/измерение без заглушек. Проверены заряд, разряд и виджет в обоих режимах; выявлен отдельный артефакт «0 мА» на Huawei — follow-up, не регрессия change.

## Workflow follow-up

- После реализации и проверки на устройстве архивировать change штатным процессом OpenSpec.
