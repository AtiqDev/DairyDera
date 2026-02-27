package com.example.dairypos

import com.example.dairypos.DatabaseHelper
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class MapCaptureActivity : AppCompatActivity() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private var customerId: Int = 0

    private lateinit var txtStatus: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnBack: Button

    // Launcher to request location permission explicitly
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                fetchAndSaveLocationSafely()
            } else {
                showError("Location permission denied. Please enable it in Settings.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_capture)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        customerId = intent.getIntExtra("customerId", 0)

        txtStatus = findViewById(R.id.txtErrorMessage)
        btnRetry = findViewById(R.id.btnRetry)
        btnBack = findViewById(R.id.btnBack)

        btnRetry.setOnClickListener { requestLocationPermission() }
        btnBack.setOnClickListener { finish() }

        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        val hasPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            fetchAndSaveLocationSafely()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun fetchAndSaveLocationSafely() {
        // Double-check at runtime (this line keeps Lint happy)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showError("Permission not granted yet.")
            return
        }

        hideError()
        txtStatus.text = "Fetching location..."
        txtStatus.visibility = TextView.VISIBLE

        fusedClient.lastLocation
            .addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    saveLocationToDB(loc)
                } else {
                    showError("Unable to fetch location — please retry.")
                }
            }
            .addOnFailureListener { err ->
                showError("Error fetching location: ${err.message}")
            }
    }

    private fun saveLocationToDB(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        val acc = loc.accuracy.toDouble()

        try {
            val db = DatabaseHelper(this)
            db.insertCustomerLocation(customerId, lat, lon, acc)
            Toast.makeText(
                this,
                "Location saved (±${acc.toInt()}m)",
                Toast.LENGTH_SHORT
            ).show()

            val result = Intent().apply {
                putExtra("customerId", customerId)
                putExtra("Latitude", lat)
                putExtra("Longitude", lon)
            }
            setResult(RESULT_OK, result)
            finish()

        } catch (e: Exception) {
            showError("Database error: ${e.message}")
        }
    }

    private fun showError(msg: String) {
        txtStatus.text = msg
        txtStatus.visibility = TextView.VISIBLE
    }

    private fun hideError() {
        txtStatus.text = ""
        txtStatus.visibility = TextView.GONE
    }
}
