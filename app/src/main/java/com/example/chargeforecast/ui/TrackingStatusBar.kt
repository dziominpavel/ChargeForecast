package com.example.chargeforecast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingLiveness
import com.example.chargeforecast.tracking.TrackingStatus
import com.example.chargeforecast.ui.theme.ChargeDimens

// Постоянная строка состояния слежения. Она не конкурирует с главным
// числом «до 100%», но всегда объясняет, обновляются ли данные, и даёт
// действие, если данные замерли или фоновое слежение не подтверждено
// (спека background-tracking, design D2).
@Composable
fun TrackingStatusBar(
    health: TrackingHealth,
    // Возраст последнего замера в минутах: «данные не обновляются N мин».
    dataAgeMinutes: Long,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = TrackingStatusText.of(health, dataAgeMinutes)
    Surface(
        // Edge-to-edge: строка не должна уходить под системную навигацию.
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = ChargeDimens.StatusBarPaddingHorizontal,
                vertical = ChargeDimens.StatusBarPaddingVertical
            ),
            verticalArrangement = Arrangement.spacedBy(ChargeDimens.GapTiny)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChargeDimens.GapSmall)
            ) {
                Box(
                    modifier = Modifier
                        .size(ChargeDimens.StatusDotSize)
                        .background(dotColor(health), CircleShape)
                )
                Text(
                    // Возраст есть только у «данных не обновляются»: у
                    // остальных строк формат без аргумента.
                    text = if (status.ageMinutes > 0L) {
                        stringResource(status.textRes, status.ageMinutes)
                    } else {
                        stringResource(status.textRes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                when (status.action) {
                    TrackingAction.RESUME -> TextButton(onClick = onRestart) {
                        Text(stringResource(status.actionLabelRes ?: status.textRes))
                    }
                    TrackingAction.OPEN_SETTINGS -> TextButton(onClick = onOpenSettings) {
                        Text(stringResource(status.actionLabelRes ?: status.textRes))
                    }
                    TrackingAction.NONE -> Unit
                }
            }
            status.hintRes?.let { hint ->
                Text(
                    text = stringResource(hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun dotColor(health: TrackingHealth): Color = when {
    // Отказ системы — ошибка; фоновый сервис просто не подтверждён —
    // нейтрально: данные при этом могут быть свежими.
    health.status == TrackingStatus.NO_PERMISSION ||
        health.status == TrackingStatus.BLOCKED_BY_OS -> MaterialTheme.colorScheme.error
    health.liveness == TrackingLiveness.DATA_STALE -> MaterialTheme.colorScheme.error
    health.liveness == TrackingLiveness.BACKGROUND_INACTIVE ->
        MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}
