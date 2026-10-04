package org.coresense.itantra.emergency

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

data class Facility(
    val id: String,
    val name: String,
    val type: String, // HOSPITAL, FIRE_STATION, POLICE, NDRF
    val category: String, // MEDICAL, FIRE, SECURITY, FLOOD, DISASTER
    val latitude: Double,
    val longitude: Double,
    val phone: String,
    val address: String,
    val distanceMeters: Double = 0.0,
    val bearingDegrees: Double = 0.0,
    val extraDetails: String = ""
) {
    val distanceKmString: String
        get() = if (distanceMeters < 1000) "${distanceMeters.toInt()} m" else String.format("%.1f km", distanceMeters / 1000.0)

    val bearingCardinal: String
        get() = Haversine.bearingToCardinal(bearingDegrees)
}

class FacilityDirectory(private val context: Context) {

    private val facilities = mutableListOf<Facility>()

    init {
        loadDirectory()
    }

    private fun loadDirectory() {
        try {
            val stream = context.assets.open("facilities_directory.json")
            val jsonText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            val array = JSONArray(jsonText)

            facilities.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                facilities.add(
                    Facility(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = obj.getString("type"),
                        category = obj.getString("category"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        phone = obj.optString("phone", "112"),
                        address = obj.optString("address", ""),
                        extraDetails = if (obj.has("bedsAvailable")) "Beds: ${obj.getInt("bedsAvailable")}" else ""
                    )
                )
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Returns top [count] nearest facilities sorted by Haversine distance from [userLat], [userLon].
     */
    fun getNearest(userLat: Double, userLon: Double, count: Int = 5): List<Facility> {
        return facilities.map { fac ->
            val dist = Haversine.distanceMeters(userLat, userLon, fac.latitude, fac.longitude)
            val bearing = Haversine.bearingDegrees(userLat, userLon, fac.latitude, fac.longitude)
            fac.copy(distanceMeters = dist, bearingDegrees = bearing)
        }.sortedBy { it.distanceMeters }.take(count)
    }

    /**
     * Returns nearest facilities filtered by emergency category (MEDICAL, FIRE, FLOOD, SECURITY, DISASTER).
     */
    fun getPrioritySuggestions(category: String, userLat: Double, userLon: Double, count: Int = 3): List<Facility> {
        return facilities
            .filter { it.category.equals(category, ignoreCase = true) || it.type.equals(category, ignoreCase = true) }
            .map { fac ->
                val dist = Haversine.distanceMeters(userLat, userLon, fac.latitude, fac.longitude)
                val bearing = Haversine.bearingDegrees(userLat, userLon, fac.latitude, fac.longitude)
                fac.copy(distanceMeters = dist, bearingDegrees = bearing)
            }
            .sortedBy { it.distanceMeters }
            .take(count)
    }
}
