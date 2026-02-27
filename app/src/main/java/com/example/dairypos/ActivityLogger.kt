package com.example.dairypos

import com.example.dairypos.DatabaseHelper
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

object ActivityLogger {

    private val executor = Executors.newSingleThreadExecutor()

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    private fun fillDefaults(rec: ActivityRecord): ActivityRecord {
        return rec.copy(
            timestamp = rec.timestamp ?: nowIso(),
            action = rec.action.ifBlank { "UNKNOWN" },
            entity = rec.entity?.ifBlank { "GENERAL" },
            // ✅ Null-safe guards for nullable Ints
            userId = rec.userId?.takeIf { it > 0 } ?: 0,
            entityId = rec.entityId?.takeIf { it > 0 } ?: 0,
            // ✅ Ensure default zeros for location
            latitude = rec.latitude ?: 0.0,
            longitude = rec.longitude ?: 0.0,
            accuracy = rec.accuracy ?: 0.0
        )
    }

    /**
     * Logs an activity to the ActivityLog table.
     *
     * - Attaches last known location if permission is granted.
     * - Uses DBFileLock to safely coordinate with import/export operations.
     */
    fun logActivity(
        ctx: Context,
        db: DatabaseHelper,
        record: ActivityRecord,
        attachLocation: Boolean = true
    ) {
        val appCtx = ctx.applicationContext
        val base = fillDefaults(record)

        if (
            attachLocation &&
            ActivityCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val fused = LocationServices.getFusedLocationProviderClient(appCtx)
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    val enriched = if (loc != null) {
                        base.copy(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy.toDouble()
                        )
                    } else base.copy(latitude = 0.0, longitude = 0.0, accuracy = 0.0)

                    executor.execute {
                        DBFileLock.lock.lock()
                        try {
                            db.insertActivityLog(enriched)
                        } catch (_: Exception) {
                            // swallow gracefully
                        } finally {
                            DBFileLock.lock.unlock()
                        }
                    }
                }
                .addOnFailureListener {
                    executor.execute {
                        DBFileLock.lock.lock()
                        try {
                            db.insertActivityLog(
                                base.copy(latitude = 0.0, longitude = 0.0, accuracy = 0.0)
                            )
                        } catch (_: Exception) {
                        } finally {
                            DBFileLock.lock.unlock()
                        }
                    }
                }
        } else {
            // No permission or disabled — still log with zeroed coordinates
            executor.execute {
                DBFileLock.lock.lock()
                try {
                    db.insertActivityLog(
                        base.copy(latitude = 0.0, longitude = 0.0, accuracy = 0.0)
                    )
                } catch (_: Exception) {
                } finally {
                    DBFileLock.lock.unlock()
                }
            }
        }
    }
}
