# Tasks: add-app-version-info

## 1. Данные на вход экрана

- [x] 1.1 Добавить в `strings.xml` (секция «Настройки») шаблон `settings_version_info` = `%1$s %2$s · сборка %3$s` и убедиться, что сборка ресурсов проходит (`.\gradlew.bat assembleDebug` стартует без ошибок ресурсов).
- [x] 1.2 В `SettingsRoute` (`MainActivity.kt`) читать `BuildConfig.VERSION_NAME` и `BuildConfig.BUILD_DATE` и передавать их в `SettingsScreen` параметрами (по образцу `opacityPct`) — проверка: `.\gradlew.bat assembleDebug` зелёный, экран компилируется с новыми параметрами.

## 2. UI-блок версии

- [x] 2.1 `SettingsScreen`: принять параметры `versionName: String` и `buildDate: String`, добавить внизу Column весомый `Spacer(Modifier.weight(1f))` и `Text` со строкой `settings_version_info` (стиль `bodySmall` + `onSurfaceVariant`, отступ `ChargeDimens.GapSmall`, только токены темы) — проверка: `.\gradlew.bat assembleDebug` зелёный.
- [ ] 2.2 Ручная проверка на устройстве/эмуляторе по сценариям спеки: внизу настроек «ChargeForecast <версия> · сборка <дата>», блок прижат к низу, тап по нему ничего не делает, значение не меняется при обновлении сессии.

## 3. Changelog и валидация

- [x] 3.1 `CHANGELOG.md`, секция `[Unreleased]`: пользовательский блок `**Добавлено**` с буллетом о версии в настройках + инженерная деталь под `### Добавлено` (change `add-app-version-info`, первое чтение `BuildConfig` в UI, нижний блок) — проверка: `python scripts/check-version.py` завершается с exit 0.
- [x] 3.2 Финальная проверка: `.\gradlew.bat test` зелёный (сьют не должен упасть), `openspec validate add-app-version-info` без ошибок.
