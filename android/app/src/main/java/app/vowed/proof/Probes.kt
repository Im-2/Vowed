package app.vowed.proof

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Step counter since boot (TYPE_STEP_COUNTER). The check-in uses the difference to a baseline the app saved earlier. */
class StepProbe(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val _total = MutableStateFlow<Long?>(null)
    val total: StateFlow<Long?> = _total.asStateFlow()
    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        _total.value = e.values[0].toLong()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
}

/** Location fixes from the best available provider. Raw coordinates never leave the phone; only "inside" and dwell seconds do. */
class LocationProbe(context: Context) : LocationListener {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Location?>(null)
    val fix: StateFlow<Location?> = _fix.asStateFlow()

    @Suppress("MissingPermission")
    fun start(): Boolean {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return false
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, 5_000L, 0f, this, android.os.Looper.getMainLooper()) }
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { if (_fix.value == null) _fix.value = it }
        }
        return true
    }

    @Suppress("MissingPermission")
    fun stop() = lm.removeUpdates(this)

    override fun onLocationChanged(l: Location) {
        _fix.value = l
    }

    companion object {
        fun distanceMeters(a: Location, lat: Double, lon: Double): Double {
            val out = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, lat, lon, out)
            return out[0].toDouble()
        }
    }
}

/** Foreground time of chosen apps (UsageStatsManager). The user grants Usage access in system settings; nothing else is read. */
class UsageProbe(private val context: Context) {
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Installed packages whose label contains [name] (for example "TikTok"); empty if none is installed. */
    @Suppress("QueryPermissionsNeeded")
    fun packagesNamed(name: String): Set<String> {
        val pm = context.packageManager
        val wanted = name.lowercase()
        return pm.getInstalledApplications(0)
            .filter { runCatching { pm.getApplicationLabel(it).toString().lowercase() }.getOrDefault("").contains(wanted) }
            .map { it.packageName }.toSet()
    }

    fun foregroundSeconds(packages: Set<String>, from: Long, to: Long): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from * 1000, to * 1000)
        val list = ArrayList<UsageEvt>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val fg = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> false
                else -> continue
            }
            list += UsageEvt(e.packageName, e.timeStamp / 1000, fg)
        }
        return ProofMath.foregroundSeconds(list, packages, from, to)
    }
}

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
