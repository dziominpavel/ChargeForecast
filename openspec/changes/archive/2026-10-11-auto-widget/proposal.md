## Why

Прогноз живёт только пока приложение открыто на экране. «Сколько осталось» — это число, которое нужно смотреть взгляда, не открывая приложение, и оно должно появляться в момент вставки кабеля. Сегодня вставку кабеля не замечает никто: `ACTION_POWER_CONNECTED` manifest-receiver'у не доставляется (нет в исключениях неявных broadcast с API 26), а foreground service из фона с API 31 поднять нельзя (`ForegroundServiceStartNotAllowedException`). Значит, чтобы поймать кабель, мониторинг обязан быть жив **до** подключения — то есть приложению нужен собственный процесс, который переживает закрытие экрана.

Вторая половина проблемы — продуктовая: приложение отвечает только на вопрос «сколько до 100%». Вопрос «сколько ещё проработает телефон» не задаётся вовсе, хотя именно он нужен в простое, когда кабеля нет.

## What Changes

- **Постоянный foreground service** `ChargeForecastService` держит `BatteryMonitor`/`ChargeSessionMonitor` живыми после закрытия приложения: старт при открытии приложения, регистрация runtime-receiver на `ACTION_POWER_CONNECTED`/`_DISCONNECTED`, перезапуск после перезагрузки через `BOOT_COMPLETED`. Уведомление сервиса — канал `IMPORTANCE_MIN`: служебная строка, а не поверхность продукта.
- **Виджет домашнего экрана** как вторая поверхность `SessionState` (наравне с `SessionScreen`, из того же единого экземпляра монитора): на зарядке — отсчёт до 100%, в простое — оценка оставшегося времени работы.
- **Оценка оставшегося времени работы**: средний ток разряда (EWMA) накапливается **только** пока телефон не заряжается, персистится в `SessionCache` и переживает перезапуск процесса; часы = остаток мкА·ч / средний ток. Оценка всегда приблизительная — это осознанное решение, а не задержка: разряд зависит от будущего использования и не может быть точным.
- `BatterySnapshot` перестаёт выбрасывать ток разряда (`currentMa` сейчас отсекается при `!charging`) — данные нужны оценщику.
- **Политика простоя в `charge-forecast` меняется**: вместо опроса каждые 10 с в простое (круглосуточно, раз сервис теперь живёт всегда) — остановка опроса и пробуждение по broadcast подключения.

## Capabilities

### New Capabilities

- `background-tracking`: фоновое слежение, переживающее закрытие приложения и перезагрузку — старт сервиса, детект подключения без открытого приложения, минимальное служебное уведомление, ограничения платформы (foreground service type, `POST_NOTIFICATIONS`, запуск из фона).
- `battery-widget`: виджет домашнего экрана как вторая поверхность состояния сессии — два режима (простой / зарядка), обновление, тап на открытие приложения.
- `discharge-estimate`: оценка оставшегося времени работы телефона по среднему расходу — накопление только на разряде, персистентность через перезапуск, честность при отсутствии истории.

### Modified Capabilities

- `charge-forecast`: политика опроса в простое — вместо интервала 10 с непрерывно сервис останавливает цикл опроса и пробуждается broadcast'ом подключения; сценарий «Экономия на разряде» уточняется соответственно.

## Impact

- `app/src/main/AndroidManifest.xml` — `ChargeForecastService`, `BootCompletedReceiver`, `BatteryWidgetProvider`, разрешения (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`), `appwidget-provider`.
- `app/src/main/java/.../service/ChargeForecastService.kt` (новое), `service/BootCompletedReceiver.kt` (новое).
- `app/src/main/java/.../widget/BatteryWidgetProvider.kt` (новое), `widget/WidgetText.kt` (новое), `res/xml/battery_widget_info.xml` (новое), `res/layout/` (новое).
- `app/src/main/java/.../forecast/DischargeEstimator.kt` (новое, чистый), `session/SessionCache.kt` (ключи расхода), `battery/BatterySnapshot.kt` (ток разряда), `ChargeForecastApplication.kt` (владение оценщиком), `ui/SessionViewModel.kt` (переход на `SharedFlow` ошибок вместо `StateFlow`, чтобы устаревшая ошибка не переигрывалась при каждом подписчике).
- Тесты: `DischargeEstimatorTest`, `SessionCacheTest` (накопитель/восстановление), `BatterySnapshotTest` (ток разряда), `ChargeSessionMonitorTest` (пробуждение по событию).
- Зависимости — нет: виджет на `RemoteViews`/`AppWidgetProvider`, без Glance.
- Проверка на устройстве автора обязательна: EMUI/Huawei убивает foreground service — риск фичи тихо не работать.
