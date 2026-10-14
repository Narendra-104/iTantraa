package org.coresense.itantra.emergency

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class GpsState {
    object Searching : GpsState()
    object NoFix : GpsState()
    object PermissionDenied : GpsState()
    object ProviderDisabled : GpsState()
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val timestamp: Long
    ) : GpsState()
}

/**
 * GpsLocationProvider:
 * - Genuine Android GPS / Location Services hardware coordinates acquisition
 * - Continuous location tracking and single-fix modes
 * - Strict coordinate validation (rejects uninitialized (0,0) and invalid range)
 * - Explicit permission denial and disabled location handling
 * - NEVER substitutes fake or simulated coordinates!
 */
class GpsLocationProvider(private val context: Context) : LocationListener {

    private val fusedClient = try {
        LocationServices.getFusedLocationProviderClient(context)
    } catch (_: Exception) {
        null
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _gpsState = MutableStateFlow<GpsState>(GpsState.Searching)
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    private var isContinuousListening = false

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val netEnabled = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        return gpsEnabled || netEnabled
    }

    @SuppressLint("MissingPermission")
    fun requestLocation() {
        if (!hasLocationPermission()) {
            _gpsState.value = GpsState.PermissionDenied
            return
        }
        if (!isLocationEnabled()) {
            _gpsState.value = GpsState.ProviderDisabled
            return
        }

        if (_gpsState.value !is GpsState.Fix) {
            _gpsState.value = GpsState.Searching
        }

        fusedClient?.lastLocation?.addOnSuccessListener { loc: Location? ->
            if (loc != null && isValidCoordinate(loc)) {
                updateFix(loc)
            } else {
                fallbackToSystemGps()
            }
        }?.addOnFailureListener {
            fallbackToSystemGps()
        } ?: fallbackToSystemGps()
    }

    @SuppressLint("MissingPermission")
    fun startContinuousUpdates(intervalMs: Long = 10_000L, minDistanceM: Float = 2.0f) {
        if (!hasLocationPermission()) {
            _gpsState.value = GpsState.PermissionDenied
            return
        }
        if (!isLocationEnabled()) {
            _gpsState.value = GpsState.ProviderDisabled
            return
        }

        isContinuousListening = true
        if (_gpsState.value !is GpsState.Fix) {
            _gpsState.value = GpsState.Searching
        }

        // 1. Immediately inspect last known location
        val lm = locationManager ?: run {
            _gpsState.value = GpsState.NoFix
            return
        }

        try {
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLoc = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                netLoc != null -> netLoc
                else -> null
            }
            if (bestLoc != null && isValidCoordinate(bestLoc)) {
                updateFix(bestLoc)
            }
        } catch (_: Exception) {
        }

        // 2. Request recurring updates from GPS and Network providers
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, minDistanceM, this)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs, minDistanceM, this)
            }
        } catch (e: Exception) {
            if (_gpsState.value !is GpsState.Fix) {
                _gpsState.value = GpsState.NoFix
            }
        }
    }

    fun stopContinuousUpdates() {
        isContinuousListening = false
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToSystemGps() {
        val lm = locationManager ?: run {
            _gpsState.value = GpsState.NoFix
            return
        }

        try {
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLoc = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                netLoc != null -> netLoc
                else -> null
            }

            if (bestLoc != null && isValidCoordinate(bestLoc)) {
                updateFix(bestLoc)
            } else {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null)
                } else {
                    _gpsState.value = GpsState.NoFix
                }
            }
        } catch (_: Exception) {
            _gpsState.value = GpsState.NoFix
        }
    }

    private fun isValidCoordinate(loc: Location): Boolean {
        if (loc.latitude < -90.0 || loc.latitude > 90.0) return false
        if (loc.longitude < -180.0 || loc.longitude > 180.0) return false
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false
        return true
    }

    private fun updateFix(loc: Location) {
        if (!isValidCoordinate(loc)) return
        _gpsState.value = GpsState.Fix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            timestamp = loc.time
        )
    }

    override fun onLocationChanged(location: Location) {
        updateFix(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        if (isContinuousListening) {
            startContinuousUpdates()
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (!isLocationEnabled()) {
            _gpsState.value = GpsState.ProviderDisabled
        }
    }
}
