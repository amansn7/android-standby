package dev.standby

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.net.URL
import kotlin.math.roundToInt

class StandbyActivity : Activity() {
    // Like iPhone StandBy: leave when the charger is pulled.
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(StandbyView(this, onLongPress = ::openNotificationAccess))
        registerReceiver(unplugged, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        ChargeWatcher.start(this)

        val missing = listOfNotNull(Manifest.permission.ACCESS_COARSE_LOCATION,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) nextSetupStep() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        getSystemService(NotificationManager::class.java).cancel(ChargeWatcher.TRIGGER_ID)
    }

    override fun onDestroy() { unregisterReceiver(unplugged); super.onDestroy() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        Weather.refresh(this, force = true)
        nextSetupStep()
    }

    /** Opens at most one settings screen per launch, each only once; long-press reopens notification access. */
    private fun nextSetupStep() {
        val prefs = getPreferences(MODE_PRIVATE)
        fun once(key: String, needed: Boolean, open: () -> Unit): Boolean {
            if (!needed || prefs.getBoolean(key, false)) return false
            prefs.edit().putBoolean(key, true).apply(); open(); return true
        }
        once("askedFullScreen", Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } || once("asked", !notificationAccessGranted(), ::openNotificationAccess)
    }

    private fun notificationAccessGranted() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

/**
 * Waits for the charger and pops Standby over the lock screen via a full-screen intent
 * (same mechanism alarm apps use). Started from the app and on boot.
 */
class ChargeWatcher : Service() {
    private val plugged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showStandby()
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Waiting for charger", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_TRIGGER, "Open on charge", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, CH_SERVICE).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Standby opens when you plug in").setContentIntent(openStandby()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        registerReceiver(plugged, IntentFilter(Intent.ACTION_POWER_CONNECTED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { unregisterReceiver(plugged); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    // Screen off/locked -> launches the activity directly; phone in use -> heads-up "tap to open".
    private fun showStandby() = getSystemService(NotificationManager::class.java).notify(TRIGGER_ID,
        Notification.Builder(this, CH_TRIGGER).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Standby").setContentText("Charging — tap to open Standby")
            .setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).setTimeoutAfter(30_000)
            .setContentIntent(openStandby()).setFullScreenIntent(openStandby(), true).build())

    private fun openStandby() = PendingIntent.getActivity(this, 0,
        Intent(this, StandbyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val TRIGGER_ID = 2
        private const val CH_SERVICE = "watcher"; private const val CH_TRIGGER = "trigger"
        fun start(c: Context) = c.startForegroundService(Intent(c, ChargeWatcher::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { ChargeWatcher.start(c) }
}

/** Android "screen saver" — starts automatically while charging/docked. Back/power exits. */
class StandbyDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = true // so swipes switch widgets
        setContentView(StandbyView(this))
    }
}

data class Note(val app: String, val icon: Drawable?, val title: String, val text: String, val at: Long)

/** Feeds new notifications to the standby screen. Needs "Notification access" granted by the user. */
class NotificationFeed : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.packageName == packageName) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val pm = packageManager
        val (label, icon) = runCatching {
            val ai = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
        }.getOrDefault(sbn.packageName to null)
        latest = Note(label, icon, title, n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
            System.currentTimeMillis())
    }

    companion object { @Volatile var latest: Note? = null }
}

data class WeatherNow(val city: String, val temp: Int, val code: Int, val hi: Int, val lo: Int) {
    val label get() = when (code) {
        0 -> "Sunny"; 1, 2 -> "Partly Cloudy"; 3 -> "Cloudy"; 45, 48 -> "Foggy"
        in 51..57 -> "Drizzle"; in 61..67, in 80..82 -> "Rain"; in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorm"; else -> "—"
    }
    val emoji get() = when (code) {
        0 -> "☀️"; 1, 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "🌨️"; in 95..99 -> "⛈️"; else -> ""
    }
}

/** Open-Meteo (free, no API key) + device coarse location. Refreshes every 30 min. */
object Weather {
    @Volatile var now: WeatherNow? = null
    @Volatile var status = "Loading weather…"
    @Volatile private var lastFetch = 0L
    @Volatile private var busy = false

    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context, force: Boolean = false) {
        val age = System.currentTimeMillis() - lastFetch
        // a location request can silently expire without a callback, so "busy" lapses after a minute
        if ((busy && age < 60_000) || (!busy && !force && age < 30 * 60_000)) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status = "Allow location for weather"; return
        }
        busy = true; lastFetch = System.currentTimeMillis()
        val lm = ctx.getSystemService(LocationManager::class.java)
        val known = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
        if (known != null) fetch(ctx, known)
        else if (provider != null)
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { fetch(ctx, it) }, Looper.getMainLooper())
        else { status = "Turn on location for weather"; busy = false; lastFetch = 0 }
    }

    private fun fetch(ctx: Context, loc: Location) = Thread {
        try {
            val j = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}" +
                "&longitude=${loc.longitude}&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1").readText())
            val cur = j.getJSONObject("current"); val day = j.getJSONObject("daily")
            @Suppress("DEPRECATION")
            val city = runCatching { Geocoder(ctx).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.locality }
                .getOrNull() ?: ""
            now = WeatherNow(city, cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"),
                day.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                day.getJSONArray("temperature_2m_min").getDouble(0).roundToInt())
        } catch (e: Exception) {
            status = "Weather offline"; lastFetch = System.currentTimeMillis() - 25 * 60_000 // retry in ~5 min
        } finally { busy = false }
    }.start()
}
