package org.coresense.itantra.emergency

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class GpsState {
    object Searching : GpsState()
    object NoFix : GpsState()
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val timestamp: Long
    ) : GpsState()
}

/**
 * GpsLocationProvider:
 * - FusedLocationProviderClient with LocationManager fallback
 * - Visible "No Fix" state when GPS is unavailable
 * - NEVER provides a fake default city or simulated coordinate!
 */
class GpsLocationProvider(private val context: Context) : LocationListener {

    private val fusedClient = try {
        LocationServices.getFusedLocationProviderClient(context)
    } catch (e: Exception) {
        null
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _gpsState = MutableStateFlow<GpsState>(GpsState.Searching)
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun requestLocation() {
        _gpsState.value = GpsState.Searching

        fusedClient?.lastLocation?.addOnSuccessListener { loc: Location? ->
            if (loc != null) {
                updateFix(loc)
            } else {
                fallbackToSystemGps()
            }
        }?.addOnFailureListener {
            fallbackToSystemGps()
        } ?: fallbackToSystemGps()
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

            if (bestLoc != null) {
                updateFix(bestLoc)
            } else {
                // Request single update
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null)
                } else {
                    _gpsState.value = GpsState.NoFix
                }
            }
        } catch (e: Exception) {
            _gpsState.value = GpsState.NoFix
        }
    }

    private fun updateFix(loc: Location) {
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
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        _gpsState.value = GpsState.NoFix
    }
}
