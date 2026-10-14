package com.example.chargeforecast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chargeforecast.BuildConfig
import com.example.chargeforecast.service.ChargeForecastService
import com.example.chargeforecast.settings.SettingsCache
import com.example.chargeforecast.ui.SessionScreen
import com.example.chargeforecast.ui.SessionViewModel
import com.example.chargeforecast.ui.SettingsScreen
import com.example.chargeforecast.ui.theme.ChargeForecastTheme
import com.example.chargeforecast.widget.BatteryWidgetProvider

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Слежение поднимаем при открытии: дальше оно живёт само и
        // после перезагрузки (BootCompletedReceiver).
        ChargeForecastService.start(this)
        enableEdgeToEdge()
        setContent {
            ChargeForecastTheme {
                ChargeForecastApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargeForecastApp(viewModel: SessionViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // Ручная навигация (design D3): второй экран — настройки, состояние
    // сессии продолжает течь, ViewModel не пересоздаётся.
    var showSettings by remember { mutableStateOf(false) }

    // Системная «назад» возвращает на экран сессии, а не сворачивает
    // приложение (спека app-settings). На главном экране «назад»
    // остаётся системным — свернуть приложение.
    BackHandler(enabled = showSettings) { showSettings = false }

    // Видимые ошибки системы — в Snackbar, не глушим (см. AGENTS.md).
    // Поток событий: каждое сообщение показывается ровно один раз, от
    // ручного «подтверждения» ошибки отказались (design D7).
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        if (showSettings) {
            SettingsRoute(
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            SessionScreen(
                state = state,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

// Слой между экраном настроек и хранилищем (design D2, D4): читает
// значение из кэша, пишет обратно и немедленно толкает виджет — менять
// прозрачность можно прямо со стоящего на домашнем экране виджета.
@Composable
private fun SettingsRoute(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cache = remember { SettingsCache.from(context) }
    var opacityPct by remember { mutableStateOf(cache.loadWidgetOpacityPct()) }

    SettingsScreen(
        opacityPct = opacityPct,
        onOpacityChange = { pct ->
            opacityPct = pct
            cache.saveWidgetOpacityPct(pct)
            // Применение без ожидания системного обновления (спека
            // app-settings): толкаем текущее состояние во все ячейки.
            BatteryWidgetProvider.pushAll(
                context,
                BatteryWidgetProvider.currentModel(context)
            )
        },
        // Версия и дата сборки фиксируются на момент сборки (спека
        // app-settings): экран не знает про BuildConfig, значения
        // приходят параметром — по образцу opacityPct.
        versionName = BuildConfig.VERSION_NAME,
        buildDate = BuildConfig.BUILD_DATE,
        modifier = modifier
    )
}
