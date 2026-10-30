package com.example.chargeforecast

import android.app.Application
import com.example.chargeforecast.battery.BatteryMonitor
import com.example.chargeforecast.forecast.DischargeEstimator
import com.example.chargeforecast.forecast.DischargeJournal
import com.example.chargeforecast.session.BootIdentity
import com.example.chargeforecast.session.ChargeSessionMonitor
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionRuntimeStore
import com.example.chargeforecast.widget.BatteryWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

// Ручной DI: один монитор сессии на процесс для экрана, сервиса и виджета.
// Application.onCreate() выполняется при старте любого компонента —
// поэтому владельцы здесь, а не в сервисе (design D6).
class ChargeForecastApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var batteryMonitor: BatteryMonitor
        private set

    lateinit var sessionMonitor: ChargeSessionMonitor
        private set

    lateinit var sessionCache: SessionCache
        private set

    // Машинное состояние: boot identity, окно разряда, ёмкость и live-снимок
    // активной сессии. Отдельный prefs-файл, исключённый из backup.
    lateinit var sessionRuntimeStore: SessionRuntimeStore
        private set

    // Накопитель расхода — свойство процесса, не сессии: живёт дольше
    // любой одной зарядки и восстанавливается после убийства процесса.
    lateinit var dischargeEstimator: DischargeEstimator
        private set

    // Журнал наблюдений: что видел датчик и что вышло в окне — выгрузка
    // через adb pull для разбора «откуда 40 минут» (design D5).
    lateinit var dischargeJournal: DischargeJournal
        private set

    private lateinit var widgetUpdater: BatteryWidgetUpdater

    override fun onCreate() {
        super.onCreate()
        val bootIdentity = BootIdentity.current(this)
        sessionCache = SessionCache.create(this)
        sessionRuntimeStore = SessionRuntimeStore.create(this)
        sessionRuntimeStore.saveBootIdentity(bootIdentity)

        dischargeJournal = DischargeJournal(File(filesDir, JOURNAL_FILE_NAME))
        dischargeEstimator = DischargeEstimator(
            onPersist = { sessionRuntimeStore.saveDischarge(it) },
            onObservation = dischargeJournal::onObservation,
            initialBootCount = bootIdentity.bootCount
        ).also {
            it.restore(
                state = sessionRuntimeStore.loadDischarge(),
                currentBootCount = bootIdentity.bootCount,
                nowElapsedMs = bootIdentity.elapsedRealtimeMs
            )
        }

        // Политика восстановления: смерть процесса в той же загрузке —
        // продолжаем сессию; ребут или backup — закрываем старую по
        // последнему уровню и стартуем без неё.
        val pendingSession = sessionRuntimeStore.loadActiveSession()
        val resumeSession = pendingSession?.takeIf {
            BootIdentity.sameBoot(
                savedBootCount = it.bootCount,
                savedElapsedRealtimeMs = it.lastUpdatedAtMs,
                currentBootCount = bootIdentity.bootCount,
                currentElapsedRealtimeMs = bootIdentity.elapsedRealtimeMs
            )
        }
        if (pendingSession != null && resumeSession == null) {
            sessionCache.finalizeActiveSession(pendingSession, sessionRuntimeStore)
        }

        batteryMonitor = BatteryMonitor(this, appScope)
        sessionMonitor = ChargeSessionMonitor(
            batteryMonitor.snapshots,
            appScope,
            cache = sessionCache,
            dischargeEstimator = dischargeEstimator,
            runtimeStore = sessionRuntimeStore,
            bootCount = bootIdentity.bootCount,
            initialSnapshot = resumeSession
        )
        batteryMonitor.start()
        // Виджет получает то же состояние, что экран и сервис — одним
        // экземпляром движка на процесс (design D5, D6).
        widgetUpdater = BatteryWidgetUpdater(this)
        appScope.launch {
            sessionMonitor.state.collect {
                // Журнал подписывает строки последней оценкой движка.
                dischargeJournal.onEstimate(it.discharge.remainingSec)
                widgetUpdater.onState(it)
            }
        }
    }

    override fun onTerminate() {
        batteryMonitor.stop()
        appScope.cancel()
        super.onTerminate()
    }

    companion object {
        private const val JOURNAL_FILE_NAME = "discharge-journal.txt"
    }
}

// Доступ к монитору из Activity без DI-фреймворка.
fun Application.sessionMonitor(): ChargeSessionMonitor =
    (this as ChargeForecastApplication).sessionMonitor
