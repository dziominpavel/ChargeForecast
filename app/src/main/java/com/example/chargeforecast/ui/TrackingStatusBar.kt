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
import androidx.compose.ui.res.stringResource
import com.example.chargeforecast.R
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingStatus
import com.example.chargeforecast.ui.theme.ChargeDimens

// Постоянная строка состояния слежения. Она не конкурирует с главным
// числом «до 100%», но всегда объясняет, обновляются ли данные, и даёт
// действие, если слежение остановлено.
@Composable
fun TrackingStatusBar(
    health: TrackingHealth,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                        .background(dotColor(health.status), CircleShape)
                )
                Text(
                    text = stringResource(statusTextRes(health.status)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                when {
                    health.needsRestart -> TextButton(onClick = onRestart) {
                        Text(stringResource(R.string.tracking_restart))
                    }
                    health.status == TrackingStatus.NO_PERMISSION ->
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.tracking_open_settings))
                        }
                }
            }
            if (health.status == TrackingStatus.BLOCKED_BY_OS) {
                Text(
                    text = stringResource(R.string.tracking_blocked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun dotColor(status: TrackingStatus) = when (status) {
    TrackingStatus.RUNNING,
    TrackingStatus.STARTING -> MaterialTheme.colorScheme.primary
    TrackingStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    TrackingStatus.BLOCKED_BY_OS,
    TrackingStatus.NO_PERMISSION -> MaterialTheme.colorScheme.error
}

private fun statusTextRes(status: TrackingStatus): Int = when (status) {
    TrackingStatus.STARTING -> R.string.tracking_starting
    TrackingStatus.RUNNING -> R.string.tracking_running
    TrackingStatus.STOPPED -> R.string.tracking_stopped
    TrackingStatus.BLOCKED_BY_OS -> R.string.tracking_blocked
    TrackingStatus.NO_PERMISSION -> R.string.tracking_no_permission
}
