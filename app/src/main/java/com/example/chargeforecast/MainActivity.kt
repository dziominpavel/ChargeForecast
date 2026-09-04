package com.example.chargeforecast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chargeforecast.ui.SessionScreen
import com.example.chargeforecast.ui.SessionViewModel
import com.example.chargeforecast.ui.theme.ChargeForecastTheme

class MainActivity : ComponentActivity() {

    private var notificationsDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsDenied = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotificationFlag()
        setContent {
            ChargeForecastTheme {
                ChargeForecastApp(
                    notificationsDenied = notificationsDenied,
                    onRetryNotifications = { requestNotifications() },
                    onRequestNotifications = { requestNotifications() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationFlag()
    }

    private fun refreshNotificationFlag() {
        notificationsDenied = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationsDenied = false
            return
        }
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargeForecastApp(
    notificationsDenied: Boolean,
    onRetryNotifications: () -> Unit,
    onRequestNotifications: () -> Unit,
    viewModel: SessionViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Приложение на переднем плане — поднимаем сервис, если идёт зарядка.
    LaunchedEffect(Unit) {
        onRequestNotifications()
        viewModel.ensureService()
    }
    // Разрешение дали позже (шторка настроек/диалог) — доподнимаем сервис.
    LaunchedEffect(notificationsDenied) {
        if (!notificationsDenied) viewModel.ensureService()
    }
    // Видимые ошибки системы — в Snackbar, не глушим (см. AGENTS.md).
    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissError()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        SessionScreen(
            state = state,
            notificationsDenied = notificationsDenied,
            onRetryNotifications = onRetryNotifications,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
