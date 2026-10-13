## 1. Хранилище настроек

- [x] 1.1 Создать `settings/SettingsCache.kt` по образцу `SessionCache`: обёртка над `SharedPreferences`, ключ `widget_opacity_pct` (Int, default 100), фабрика `from(context)`. Проверка: `.\gradlew.bat assembleDebug` проходит.
- [x] 1.2 Unit-тест `SettingsCache`: default 100, запись/чтение значения (напр. 60), отсутствие крэшей при пустых prefs. Проверка: `.\gradlew.bat test` зелёный.

## 2. Фон виджета с управляемым alpha

- [x] 2.1 В трёх лейаутах (`widget_battery.xml`, `widget_battery_wide.xml`, `widget_battery_narrow.xml`) вынести содержимое во вложенный контейнер, оставив `widget_background` на корне; проверить, что `@+id/widget_root` сохранился (по нему клик) и раскладка визуально не изменилась.
- [x] 2.2 В `BatteryWidgetProvider.render()` читать `SettingsCache.from(context)` и ставить alpha на корень через `setFloat(R.id.widget_root, "setAlpha", pct/100f)` — только при pct < 100 (100 — прежний путь без вызова). Проверка: ручная установка значения 60 в prefs → `pushAll` → виджет с полупрозрачным фоном, текст прежний.
- [ ] 2.3 Проверить все три бакета размера (перетащить/поставить виджеты трёх размеров): фон одинаково полупрозрачный, текст непрозрачен.

## 3. Экран настроек и навигация

- [x] 3.1 Создать `ui/SettingsScreen.kt`: заголовок «Настройки», блок «Непрозрачность фона виджета» со `Slider` (0..100, steps=9), подписью текущего значения в %, колбэк `onOpacityChange(Int)`. Только токены темы (`MaterialTheme`, `ChargeDimens`).
- [x] 3.2 В `ChargeForecastApp`: шестерёнка в `actions` слоте `TopAppBar`, локальный `showSettings` переключает `SessionScreen` ↔ `SettingsScreen`; возврат — назад на главный. Состояние сессии продолжает течь (ViewModel не пересоздаётся). Проверка: сборка + ручной проход в обе стороны.
- [x] 3.3 Привязать `SettingsScreen` к `SettingsCache`: чтение при открытии, запись + немедленный `BatteryWidgetProvider.pushAll(context, currentModel(context))` при изменении. Проверка: на домашнем экране виджет меняет прозрачность в течение секунд при движении ползунка.

## 4. Строка и проверки

- [x] 4.1 Добавить строки в `res/values/strings.xml` (заголовок настроек, подпись настройки, «%»), заменить хардкод в `SettingsScreen`. Проверка: `assembleDebug`.
- [x] 4.2 Обновить `README.md` (статус: настройки, первая настройка) и добавить пункт в `CHANGELOG.md` → `## [Unreleased]`, `**Добавлено**` (пользовательский блок по правилам versioning). Проверка: `python scripts/check-version.py` exit 0.
- [ ] 4.3 На устройстве автора (EMUI): проверить, что прозрачность реально видна на домашнем экране; если лаунчер игнорирует — зафиксировать и обсудить запасной план (11 drawable-вариантов через `setBackgroundResource`, design D1). Проверка: наблюдение на телефоне.
- [ ] 4.4 Финальная проверка: `.\gradlew.bat test` зелёный, виджет после перезагрузки устройства использует сохранённое значение (спека battery-widget, сценарий «Настройка пережила перезагрузку»).
