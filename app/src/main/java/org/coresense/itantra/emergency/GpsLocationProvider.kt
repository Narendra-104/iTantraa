package org.coresense.itantra.emergency

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
 * - High-accuracy Android GPS / Location Services hardware coordinates acquisition (Step 12).
 * - Primary: Google Play Services FusedLocationProviderClient with PRIORITY_HIGH_ACCURACY (~1s target interval).
 * - Fallback / Co-provider: Android LocationManager (GPS_PROVIDER and NETWORK_PROVIDER) for non-GMS devices.
 * - Strict coordinate validation: rejects NaN, infinite, bounds violations, and (0,0) uninitialized defaults.
 * - Freshness verification: rejects stale cached fixes and prevents low-accuracy cell tower fixes from overwriting fresh GPS fixes.
 * - NEVER substitutes fake or simulated coordinates!
 */
class GpsLocationProvider(private val context: Context) : LocationListener {

    private val fusedClient: FusedLocationProviderClient? = try {
        LocationServices.getFusedLocationProviderClient(context)
    } catch (_: Exception) {
        null
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _gpsState = MutableStateFlow<GpsState>(GpsState.Searching)
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    private var isContinuousListening = false
    private var fusedLocationCallback: LocationCallback? = null

    var onFixEmitted: ((GpsState.Fix) -> Unit)? = null

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
            if (loc != null && isValidCoordinate(loc) && isRecentFix(loc, maxAgeMs = 30_000L)) {
                updateFix(loc)
            } else {
                fallbackToSystemGps()
            }
        }?.addOnFailureListener {
            fallbackToSystemGps()
        } ?: fallbackToSystemGps()
    }

    @SuppressLint("MissingPermission")
    fun startContinuousUpdates(intervalMs: Long = 1000L, minDistanceM: Float = 0.0f) {
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

        // 1. Immediately inspect last known location if fresh (< 30s old)
        try {
            val lm = locationManager
            val gpsLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLoc = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                netLoc != null -> netLoc
                else -> null
            }
            if (bestLoc != null && isValidCoordinate(bestLoc) && isRecentFix(bestLoc, maxAgeMs = 30_000L)) {
                updateFix(bestLoc)
            }
        } catch (_: Exception) {}

        // 2. High-Accuracy FusedLocationProviderClient continuous updates (~1-second target)
        if (fusedClient != null) {
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                    .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(500L))
                    .setMinUpdateDistanceMeters(minDistanceM)
                    .setWaitForAccurateLocation(false)
                    .build()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (loc in result.locations) {
                            if (isValidCoordinate(loc)) {
                                updateFix(loc)
                            }
                        }
                    }
                }
                fusedLocationCallback = callback
                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (_: Exception) {}
        }

        // 3. Native LocationManager hardware GPS provider as co-provider / fallback
        try {
            val lm = locationManager
            if (lm != null) {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, minDistanceM, this, Looper.getMainLooper())
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs * 2, minDistanceM, this, Looper.getMainLooper())
                }
            }
        } catch (_: Exception) {
            if (_gpsState.value !is GpsState.Fix) {
                _gpsState.value = GpsState.NoFix
            }
        }
    }

    fun stopContinuousUpdates() {
        isContinuousListening = false
        fusedLocationCallback?.let { callback ->
            try { fusedClient?.removeLocationUpdates(callback) } catch (_: Exception) {}
            fusedLocationCallback = null
        }
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
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

            if (bestLoc != null && isValidCoordinate(bestLoc) && isRecentFix(bestLoc, maxAgeMs = 60_000L)) {
                updateFix(bestLoc)
            } else {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, Looper.getMainLooper())
                } else {
                    _gpsState.value = GpsState.NoFix
                }
            }
        } catch (_: Exception) {
            _gpsState.value = GpsState.NoFix
        }
    }

    fun isValidCoordinate(loc: Location): Boolean {
        if (loc.latitude.isNaN() || loc.longitude.isNaN()) return false
        if (loc.latitude.isInfinite() || loc.longitude.isInfinite()) return false
        if (loc.latitude < -90.0 || loc.latitude > 90.0) return false
        if (loc.longitude < -180.0 || loc.longitude > 180.0) return false
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false
        return true
    }

    private fun isRecentFix(loc: Location, maxAgeMs: Long = 30_000L): Boolean {
        if (loc.time <= 0L) return true
        val age = System.currentTimeMillis() - loc.time
        return age >= 0 && age <= maxAgeMs
    }

    private fun isBetterLocation(newLoc: Location, currentFix: GpsState.Fix?): Boolean {
        if (currentFix == null) return true

        val newTime = if (newLoc.time > 0L) newLoc.time else System.currentTimeMillis()
        val timeDelta = newTime - currentFix.timestamp
        val isSignificantlyNewer = timeDelta > 15_000L
        val isSignificantlyOlder = timeDelta < -15_000L
        val isNewer = timeDelta > 0

        // If the fix is significantly newer, accept it even if accuracy is somewhat lower (user is moving)
        if (isSignificantlyNewer) {
            return true
        } else if (isSignificantlyOlder) {
            // Reject old fixes delivered out of order
            return false
        }

        val accuracyDelta = (newLoc.accuracy - currentFix.accuracyMeters).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

        // If it's vastly less accurate and not substantially newer, keep current accurate fix
        if (isSignificantlyLessAccurate && !isSignificantlyNewer) {
            return false
        }

        return isNewer || isMoreAccurate
    }

    fun updateFix(loc: Location) {
        if (!isValidCoordinate(loc)) return

        val current = _gpsState.value as? GpsState.Fix
        if (!isBetterLocation(loc, current)) {
            return
        }

        val fixTimestamp = if (loc.time > 0L) loc.time else System.currentTimeMillis()
        val fix = GpsState.Fix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10.0f,
            timestamp = fixTimestamp
        )
        _gpsState.value = fix
        onFixEmitted?.invoke(fix)
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
