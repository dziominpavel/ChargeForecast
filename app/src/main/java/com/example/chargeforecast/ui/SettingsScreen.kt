package com.example.chargeforecast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.chargeforecast.R
import com.example.chargeforecast.settings.SettingsCache
import com.example.chargeforecast.ui.theme.ChargeDimens
import kotlin.math.roundToInt

// Экран настроек — вторая поверхность приложения (ручная навигация в
// ChargeForecastApp). Первая настройка — непрозрачность фона виджета:
// ползунок ступенями по 10% от 0 до 100. Значение пишется в кэш
// настроек и сразу толкается на виджет.
@Composable
fun SettingsScreen(
    opacityPct: Int,
    onOpacityChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Ступеньки ползунка: шаг 10% ⇒ 9 внутренних ступеней (спека app-settings).
    var sliderValue by remember(opacityPct) {
        mutableFloatStateOf(opacityPct.toFloat())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(ChargeDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ChargeDimens.GapLarge)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = stringResource(R.string.settings_widget_opacity),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(R.string.settings_widget_opacity_value, sliderValue.roundToInt()),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Slider(
            value = sliderValue,
            onValueChange = { value ->
                sliderValue = value
                onOpacityChange(value.roundToInt())
            },
            valueRange = SettingsCache.OPACITY_MIN_PCT.toFloat()
                ..SettingsCache.OPACITY_MAX_PCT.toFloat(),
            // 10 интервалов по 10% между 0 и 100.
            steps = OPACITY_SLIDER_STEPS,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.settings_widget_opacity_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Шаг настройки 10%: 11 позиций ⇒ 9 внутренних ступеней сверх краёв.
private const val OPACITY_SLIDER_STEPS = 9
