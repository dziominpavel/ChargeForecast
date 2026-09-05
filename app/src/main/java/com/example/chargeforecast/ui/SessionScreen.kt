package com.example.chargeforecast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.example.chargeforecast.R
import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.ui.theme.ChargeDimens

// Живой экран сессии — единственная поверхность продукта.

@Composable
fun SessionScreen(
    state: SessionState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(ChargeDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            ChargeDimens.GapLarge, Alignment.CenterVertically
        )
    ) {
        val snapshot = state.snapshot
        if (snapshot == null || !state.isPlugged) {
            Icon(
                imageVector = Icons.Filled.BatteryChargingFull,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ChargeDimens.HeroIconSize)
            )
            Text(
                text = stringResource(R.string.session_not_charging_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.session_not_charging_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            return
        }

        Icon(
            imageVector = Icons.Filled.BatteryChargingFull,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ChargeDimens.HeroIconSize)
        )
        Text(
            text = stringResource(R.string.session_level, snapshot.levelPct),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = chargeTypeText(snapshot.chargeType),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ElectricalLine(snapshot = snapshot)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(ChargeDimens.CardCorner)
        ) {
            Column(
                modifier = Modifier.padding(ChargeDimens.CardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ChargeDimens.GapSmall)
            ) {
                when {
                    state.forecast.isFull -> Text(
                        text = stringResource(R.string.session_charged),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    state.forecast.remainingSec == null -> Text(
                        text = measuringOrCollecting(state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        val context = LocalContext.current
                        val speed = state.forecast.speedPctPerMin
                        if (speed != null) {
                            Text(
                                text = stringResource(
                                    R.string.session_speed,
                                    String.format(java.util.Locale.US, "%.1f", speed)
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.session_remaining,
                                ForecastText.formatRemaining(
                                    context, state.forecast.remainingSec
                                )
                            ),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = accuracyText(state.forecast.accuracy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ElectricalLine(snapshot: BatterySnapshot) {
    // Живые сырые данные — видны сразу, не ждут прогноза.
    // Если датчик тока недоступен, строка просто короче (fallback).
    val parts = mutableListOf<String>()
    snapshot.currentMa?.let { ma ->
        parts += if (ma >= 1000f) {
            stringResource(
                R.string.session_live_current_a,
                String.format(java.util.Locale.US, "%.1f", ma / 1000f)
            )
        } else {
            stringResource(R.string.session_live_current_ma, ma.toInt())
        }
    }
    snapshot.voltageV?.let { v ->
        parts += stringResource(
            R.string.session_live_voltage,
            String.format(java.util.Locale.US, "%.1f", v)
        )
    }
    snapshot.temperatureC?.let { t ->
        parts += stringResource(
            R.string.session_live_temp,
            String.format(java.util.Locale.US, "%.1f", t)
        )
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun measuringOrCollecting(state: SessionState): String {
    // Ярус «Измеряю…»: живой счётчик секунд (тик 1 сек) вместо статичного
    // текста — видно, что система жива с первой секунды.
    val secs = ForecastText.measuringAgeSeconds(state.sessionAgeMs)
    return if (secs != null) stringResource(R.string.session_measuring, secs)
    else stringResource(R.string.session_collecting)
}

@Composable
private fun chargeTypeText(type: ChargeType): String = stringResource(
    when (type) {
        ChargeType.AC -> R.string.session_type_ac
        ChargeType.USB -> R.string.session_type_usb
        ChargeType.WIRELESS -> R.string.session_type_wireless
        ChargeType.NONE, ChargeType.UNKNOWN -> R.string.session_type_unknown
    }
)

@Composable
private fun accuracyText(accuracy: ForecastAccuracy): String = stringResource(
    when (accuracy) {
        ForecastAccuracy.APPROXIMATE -> R.string.session_accuracy_approx
        ForecastAccuracy.PRECISE -> R.string.session_accuracy_precise
        ForecastAccuracy.REFINING -> R.string.session_accuracy_refining
        ForecastAccuracy.COLLECTING -> R.string.session_collecting
    }
)
