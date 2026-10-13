## Context

Виджет рисуется через `RemoteViews` (3 лейаута: `widget_battery.xml`, `_wide`, `_narrow`), фон — shape-drawable `widget_background.xml` (solid `widget_surface` #141A26, радиус 16dp), навешанный на корневой `LinearLayout` (`@+id/widget_root`). `RemoteViews` не умеет Compose, поэтому цвета виджета дублируются в `res/values/colors.xml` (см. комментарий там).

Обновление виджета — push от состояния через `BatteryWidgetProvider.pushAll(context, model)`; троттлинг в `BatteryWidgetUpdater` к прямому вызову `pushAll` отношения не имеет.

Хранилище настроек в проекте уже имеет образец: `SessionCache` — тонкая обёртка над `SharedPreferences`. Навигация — ручная (нет Navigation Component), `MainActivity` держит один `Scaffold` с `TopAppBar`.

Проверено по `android.jar` (API 36): у `RemoteViews` есть публичные `setFloat(viewId, methodName, float)` и `setInt(viewId, methodName, int)`, то есть можно вызвать `View.setAlpha(float)` на любой виджетной View удалённо. Мотивация — proposal.md; требования — спеки `app-settings` и `battery-widget` этой дельты.

## Goals / Non-Goals

**Goals:**

- Непрозрачность фона виджета 0–100% (шаг 10%, по умолчанию 100%), переживает перезапуск и перезагрузку.
- Текст виджета всегда непрозрачный.
- Изменение применяется к виджету немедленно.
- Каркас экрана настроек под будущие настройки (история, профили — дорожный пункт 8).

**Non-Goals:**

- Настройки цвета текста, темы виджета, кегля шрифта.
- Настройки самого приложения (экран сессии) — только виджет.
- Экраны истории и профилей зарядок.
- Автономная Activity настроек (экран живёт внутри MainActivity).

## Decisions

**D1. Alpha только фону — через отдельный View-слой, а не alpha корня.**
`setFloat(R.id.widget_root, "setAlpha", ...)` на корне погасил бы и текст. Поэтому в каждом лейауте содержимое переносится во вложенный контейнер, а фон остаётся на корне; alpha ставится на View с фоном. Альтернативы отклонены:
- 11 копий drawable (`widget_background_0..100`) + `setBackgroundResource` — надёжнее, но 11 почти идентичных файлов и дублирование радиуса/цвета; оставляем как запасной план (см. Risks).
- `setInt(root, "setBackgroundResource", R.drawable...)` с ColorDrawable — теряет скругление 16dp.

**D2. Хранилище — новый `SettingsCache` по образцу `SessionCache`.**
Тот же приём: класс-обёртка над `SharedPreferences` + фабрика `from(context)`. Отдельный файл настроек (не общий с `SessionCache`), ключ `widget_opacity_pct: Int`, default 100. Хранение в процентах (0..100), а не float — шаг 10% делает целые числа естественным представлением.

**D3. Навигация — локальный boolean в `ChargeForecastApp`.**
`showSettings: Boolean` в состоянии composable; `TopAppBar(actions = { IconButton { шестерёнка } })`. По возврату — тот же `SessionScreen` с живым состоянием (state течёт из ViewModel непрерывно, экран настроек его не останавливает). Navigation Component не вводим — в проекте ручная навигация по решению.

**D4. Немедленное применение — прямой `pushAll` из onValueChange.**
`SettingsScreen` колбэком отдаёт новое значение; слой, держащий доступ к `Application`, вызывает `BatteryWidgetProvider.pushAll(context, BatteryWidgetProvider.currentModel(context))`. Шаг 10% ⇒ не чаще 11 вызовов за полный проход ползунка; каждый — один binder-вызов в лаунчер, допустимо. Троттлинг `BatteryWidgetUpdater` не участвует (обновление виджета не от состояния сессии).

**D5. Slider со ступенями.**
Material 3 `Slider`: `valueRange = 0f..100f`, `steps = 9` (10 интервалов = 11 ступеней). Значение округляется до ближайшей ступени перед записью.

**D6. UI — только токены темы.**
`SettingsScreen` собирается из `MaterialTheme.colorScheme` / `typography` и `ChargeDimens` (правило AGENTS.md: без inline `Color(0xFF..)` и `.dp` в новых местах).

## Risks / Trade-offs

- [EMUI-лаунчер игнорирует прозрачность фона виджета] → Проверка на устройстве автора — критерий готовности из спеки. Запасной план: набор из 11 drawable-вариантов (solid с alpha в color-ресурсе, радиус 16dp) и переключение через `setInt(root, "setBackgroundResource", id)` — тот же RemoteViews-канал, но без вызова `setAlpha`. Если не покажет и это — честно сказать пользователю, что оболочка не поддерживает прозрачность.
- [Текст на прозрачном фоне может сливаться с обоями] → Сознательное решение пользователя: гасится только фон, текст не трогаем; 0% допустим.
- [Полупрозрачный фон меняет perceived-цвет скруглений у лаунчеров, рисующих свою подложку] → На Android 12+ лаунчер сам обрезает контейнер; на более старых shape-фон с alpha отрабатывает штатно. Ручная проверка на EMUI.
- [pushAll при каждом изменении ступени] → Ограничено 11 ступенями; лаунчер терпит. Троттлинг не нужен.

## Open Questions

- Нужен ли бейдж/подпись текущего значения («60%») рядом с ползунком — косметика, решается в реализации; на спеки и задачи не влияет.
