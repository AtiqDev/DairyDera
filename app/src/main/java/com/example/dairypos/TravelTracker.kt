package com.example.dairypos

import com.example.dairypos.DatabaseHelper
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlin.math.abs

/**
 * Background travel tracker that auto-detects:
 *  - Travel Start
 *  - Travel Movement (while moving)
 *  - Travel Stop
 *
 *  and logs them via ActivityLogger into ActivityLog table.
 */
class TravelTrackerService : Service() {

    private lateinit var db: DatabaseHelper
    private lateinit var fusedClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var lastLoggedLocation: Location? = null
    private var isMoving = false
    private var lastMoveTime: Long = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleNewLocation(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = DatabaseHelper(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Tracking travel activity..."))
        startLocationUpdates()
        Log.d(TAG, "TravelTrackerService created")
    }

    // =====================================================
    // Location tracking
    // =====================================================
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Permission denied for location updates")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60_000L // every 1 minute
        ).apply {
            setMinUpdateDistanceMeters(15f)
            setMinUpdateIntervalMillis(30_000L)
        }.build()

        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        Log.d(TAG, "Travel tracking started.")
    }

    private fun handleNewLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val last = lastLocation
        lastLocation = loc

        val speed = loc.speed // m/s
        val distance = last?.distanceTo(loc) ?: 0f

        val isMovingFast = speed > 1.0 // ~3.6 km/h
        val movedSignificantly = distance > 25f

        when {
            // --- Start moving ---
            !isMoving && (isMovingFast || movedSignificantly) -> {
                isMoving = true
                lastMoveTime = now
                logTravelEvent("Travel Start", loc, "Started moving")
                updateNotification("🚶 Moving...")
                lastLoggedLocation = loc
            }

            // --- Keep moving ---
            isMoving && movedSignificantly -> {
                val lastLogged = lastLoggedLocation
                val distSinceLastLog = lastLogged?.distanceTo(loc) ?: 0f
                if (distSinceLastLog > 30f) { // log every ~30m
                    logTravelEvent(
                        "Travel Movement",
                        loc,
                        "Moved ${"%.1f".format(distSinceLastLog)}m @ ${"%.2f".format(speed)} m/s"
                    )
                    lastLoggedLocation = loc
                }
                lastMoveTime = now
            }

            // --- Stop moving ---
            isMoving && !isMovingFast && now - lastMoveTime > 10 * 60 * 1000L -> {
                isMoving = false
                logTravelEvent("Travel Stop", loc, "Stationary for >10 min")
                updateNotification("🛑 Stationary — paused tracking")
            }
        }
    }

    private fun logTravelEvent(type: String, loc: Location, note: String) {
        val record = ActivityRecord(
            userId = 1,
            action = "TRAVEL",
            entity = type, // Travel Start / Movement / Stop
            entityId = 0,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy.toDouble(),
            extra = note
        )
        ActivityLogger.logActivity(this, db, record, attachLocation = false)
        Log.d(TAG, "Logged $type @ ${loc.latitude}, ${loc.longitude} (${note})")
    }

    private fun stopLocationUpdates() {
        fusedClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Travel tracking stopped.")
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
        Log.d(TAG, "TravelTrackerService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================
    // Foreground notification
    // =====================================================
    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            "Travel Tracker",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(chan)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DairyFarmPOS Travel Tracker")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Don’t crash if POST_NOTIFICATIONS not granted — safe skip
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping notification update")
            return
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, createNotification(content))
    }

    companion object {
        private const val TAG = "TravelTrackerService"
        private const val CHANNEL_ID = "travel_tracker_channel"
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, TravelTrackerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TravelTrackerService::class.java)
            context.stopService(intent)
        }
    }
}
