package com.example.chargeforecast

import android.app.Application
import com.example.chargeforecast.battery.BatteryMonitor
import com.example.chargeforecast.session.ChargeSessionMonitor
import com.example.chargeforecast.session.SessionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// Ручной DI: один монитор сессии на процесс для экрана.
class ChargeForecastApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var batteryMonitor: BatteryMonitor
        private set

    lateinit var sessionMonitor: ChargeSessionMonitor
        private set

    lateinit var sessionCache: SessionCache
        private set

    override fun onCreate() {
        super.onCreate()
        sessionCache = SessionCache.create(this)
        batteryMonitor = BatteryMonitor(this, appScope)
        sessionMonitor = ChargeSessionMonitor(
            batteryMonitor, appScope, cache = sessionCache
        )
        batteryMonitor.start()
    }

    override fun onTerminate() {
        batteryMonitor.stop()
        appScope.cancel()
        super.onTerminate()
    }
}

// Доступ к монитору из Activity без DI-фреймворка.
fun Application.sessionMonitor(): ChargeSessionMonitor =
    (this as ChargeForecastApplication).sessionMonitor
