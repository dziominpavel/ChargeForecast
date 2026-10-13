# Tasks: estimate-from-first-second

## 1. Приор разряда (DischargeEstimator)

- [x] 1.1 Добавить в `DischargeEstimator.estimate()` уровень заряда и приор «полный заряд ≈ 24 ч»: при пустом/непригодном окне возвращается число от доли уровня, поле `collecting` удаляется из `DischargeEstimate` — проверка: новые тесты `DischargeEstimatorTest` (пустое окно → priorSec; мусорное среднее → удержание приора; устройство без счётчика → приор от уровня)
- [x] 1.2 Реализовать плавный смесь приора и измеренного: вес приора smoothstep от 0 к 2 часам наблюдаемого времени, на границе 2 ч — чистое измеренное — проверка: тесты на смесь на середине окна (между prior и measured, скачка нет) и обнуление веса к 2 ч
- [x] 1.3 Протянуть уровень из `SessionEngine.dischargeEstimate()` в `estimate()` — проверка: `SessionEngineTest`: с первым разрядным снапшотом `discharge.remainingSec != null` (не `NONE`, не null); тесты на «собираю данные» обновлены

## 2. Приор зарядки по типу подключения

- [x] 2.1 Ввести константы приора (`AC 2 ч, USB 6 ч, wireless 4 ч, unknown 3 ч` → %/мин) и подставить приор в слот истории `SessionEngine` (`loadHistoryMedian(type) ?: prior`) — проверка: `SessionEngineTest`: первая зарядка без истории — `forecast.remainingSec != null` с первой секунды, точность не COLLECTING
- [x] 2.2 Обновить `WidgetTextTest`: зарядка без истории — режим CHARGING с числом, а не COLLECTING — проверка: тесты зелёные

## 3. Формат остатка

- [x] 3.1 Переписать `ForecastText.formatRemaining`: `<60 с` — секунды, `<1 ч` — минуты, `≥1 ч` — часы и минуты (0 минут → «N ч»); удалить строки `time_minutes_seconds`, `time_hours_minutes_seconds` из `strings.xml` — проверка: сборка зелёная, ручная сверка трёх ярусов формата
- [x] 3.2 Проверить, что нигде не осталось ссылок на удалённые строки и формат — проверка: поиск по `R.string.time_minutes_seconds` и `time_hours_minutes_seconds` пуст

## 4. Виджет

- [x] 4.1 Удалить `WidgetMode.COLLECTING`; `NO_DATA` рендерить пустым (без текста); обновить `WidgetText.model()` — проверка: `WidgetTextTest` (нет COLLECTING; есть снапшот в простое → DISCHARGE с числом из приора; нет снапшота → NO_DATA пусто)
- [x] 4.2 `BatteryWidgetUpdater`: интервал толчка 1 с при режиме CHARGING с остатком <60 с, иначе прежние 15 с — проверка: тест логики троттлинга или код-ревью с ручной проверкой на устройстве (последняя минута секунды идут плавно)
- [x] 4.3 Убрать ветки строк `widget_collecting`, `widget_measuring`, `widget_no_data`, `widget_short_collecting`, `widget_short_no_data` из `BatteryWidgetProvider` и `strings.xml` — проверка: сборка зелёная, поиском по `strings.xml` эти ключи не найдены

## 5. Экран сессии

- [x] 5.1 `SessionScreen`: удалить `measuringOrCollecting` и ветку «Измеряю…» на зарядке; `accuracyText` для COLLECTING прячет строку точности; в карточке разряда строка среднего тока показывается только когда ток измерен — проверка: сборка зелёная, при зарядке без истории с первой секунды видно остаток
- [x] 5.2 Удалить из `strings.xml` строки без случая: `session_collecting`, `session_measuring`, `session_discharge_collecting`, `session_discharge_collecting_hint`, `session_discharge_avg_pending`, `session_discharge_tracked`, `notification_tracking_active` — проверка: поиском по `app/src/main` ни одна из них не используется

## 6. Уведомление сервиса

- [x] 6.1 `buildNotification()` без `.setContentText(...)` (остаются иконка и имя приложения) — проверка: сборка зелёная; на устройстве — в шторке строка без текста, шторка не двигается при вставке кабеля

## 7. Changelog и проверки

- [x] 7.1 `CHANGELOG.md`, секция `## [Unreleased]`: пользовательский блок (цифры с первой секунды без «собираю данные»; приор «полный заряд ≈ 24 ч» в простое; приор зарядки по типу подключения; секунды только в последнюю минуту; уведомление без текста) + инженерные детали под `###` — проверка: `python scripts/check-version.py` зелёный
- [x] 7.2 `.\gradlew.bat test` и `.\gradlew.bat assembleDebug` — зелёные
- [ ] 7.3 Живая проверка на устройстве автора: чистая установка → виджет разряда сразу показывает «≈ 24 ч × доля»; первая зарядка → отсчёт с первой секунды по приору типа; последняя минута зарядки → секунды идут плавно; уведомление без текста

Итог 7.2 (2026-10-13): scope change зелёный — все тесты change проходят (186/188), assembleDebug OK. Два падения DisplayFilterTest.downSlides_notJumps и DisplayFilterTest.ratchet_modelRevisionAfterStableTicks_glidesUpInSteps — предсуществующие: файлы DisplayFilter.kt/DisplayFilterTest.kt изменены в дереве недоделанным чужим change (калибровка отложена), на чистом HEAD оба зелёные — проверено stash-прогоном. Здесь не чинить; устройство (7.3) — за автором.
