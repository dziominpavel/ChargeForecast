package com.example.chargeforecast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.chargeforecast.R
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.notification.NotificationHelper
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.ui.theme.ChargeDimens

// Живой экран сессии: те же цифры, что в шторке.
private const val NOTIF_STALE_MS = 90_000L

@Composable
fun SessionScreen(
    state: SessionState,
    notificationsDenied: Boolean,
    onRetryNotifications: () -> Unit,
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = stringResource(R.string.session_not_charging_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
        LastSpeedLine(state = state)
        NotifStaleLine(state = state)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                ChargeDimens.CardCorner
            )
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
                    state.forecast.remainingMin == null -> Text(
                        text = stringResource(R.string.session_collecting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        val context = androidx.compose.ui.platform.LocalContext.current
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
                                NotificationHelper.formatRemaining(
                                    context, state.forecast.remainingMin
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

        if (notificationsDenied) {
            Text(
                text = stringResource(R.string.session_notifications_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetryNotifications) {
                Text(stringResource(R.string.session_retry_notifications))
            }
        }
    }
}

@Composable
private fun ElectricalLine(snapshot: com.example.chargeforecast.battery.BatterySnapshot) {
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
private fun LastSpeedLine(state: SessionState) {
    // Справочно из кэша, пока текущая скорость уточняется.
    // На точном замере строка не нужна — цифры уже свои.
    if (state.forecast.accuracy == ForecastAccuracy.PRECISE) return
    val last = state.lastSpeedPctPerMin ?: return
    Text(
        text = stringResource(
            R.string.session_last_speed,
            String.format(java.util.Locale.US, "%.1f", last)
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun NotifStaleLine(state: SessionState) {
    // Диагностика шторки: сервис пишет пульс при каждом обновлении.
    // Пульса нет >90 сек — шторка мертва, говорим прямо и что делать.
    val heartbeat = state.lastNotifUpdateMs
    val stale = heartbeat == null ||
        System.currentTimeMillis() - heartbeat > NOTIF_STALE_MS
    if (!stale) return
    Text(
        text = stringResource(R.string.session_notif_stale),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
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
        ForecastAccuracy.COLLECTING -> R.string.session_collecting
    }
)
