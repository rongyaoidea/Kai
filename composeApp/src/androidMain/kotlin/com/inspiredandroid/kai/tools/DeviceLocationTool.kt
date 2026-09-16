package com.inspiredandroid.kai.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent.inject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/** Last-known fixes older than this are re-requested before being reported. */
private const val STALE_FIX_MS = 5 * 60 * 1000L

/**
 * Device location via the platform provider (GPS/network), gated on the runtime
 * LOCATION permission. Android-only; everywhere else the IP-based
 * `get_location_from_ip` tool remains the coarse alternative.
 *
 * Strategy: a fresh last-known fix from any enabled provider answers instantly;
 * otherwise one bounded location update is requested (which is what triggers the
 * system's location acquisition). Reverse geocoding is best-effort — a missing
 * address never fails the tool, since coordinates are the payload.
 */
object DeviceLocationTool {

    const val ID = "get_device_location"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Get Device Location",
        description = "Get the device's precise location (GPS/network) after asking for permission",
        nameRes = null,
        descriptionRes = null,
    )

    fun create(permissionController: PermissionController): Tool = object : Tool {
        override val timeout = 90.seconds

        override val schema = ToolSchema(
            name = ID,
            description = "Get the device's current location (latitude, longitude, accuracy, and a best-effort address) " +
                "from the phone's location services. Use this when the user asks where they are or needs local, " +
                "device-accurate information (weather, nearby places, distances). The first call asks for the " +
                "Location permission; if the user denies it, fall back to get_location_from_ip for a coarse " +
                "city-level estimate. Reading the location is only possible while Kai is in the foreground.",
            parameters = mapOf(
                "timeout_seconds" to ParameterSchema("integer", "How long to wait for a fresh fix (default 12, max 30)", false),
                "include_address" to ParameterSchema("boolean", "Reverse-geocode coordinates into a street address (default true)", false),
            ),
        )

        @SuppressLint("MissingPermission")
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!permissionController.hasPermission()) {
                // A background run (scheduled task, heartbeat) cannot show the system
                // dialog; fail fast instead of stalling for the controller timeout.
                if (!isInteractiveRun()) {
                    return mapOf(
                        "success" to false,
                        "error" to "Location permission is not granted and this run cannot ask for it. Ask from a chat so the user can approve, or use get_location_from_ip.",
                    )
                }
                if (!permissionController.requestPermission()) {
                    return mapOf(
                        "success" to false,
                        "error" to "Location permission denied. The user can grant it in Android Settings > Apps > Kai > Permissions, or use get_location_from_ip for a coarse estimate.",
                    )
                }
            }

            val context: Context by inject(Context::class.java)
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return mapOf("success" to false, "error" to "Location services are unavailable on this device")

            val timeoutSeconds = (((args["timeout_seconds"] as? Number)?.toInt() ?: 12).coerceIn(1, 30)).toLong()
            val location = bestAvailableFix(manager, timeoutSeconds)
                ?: return mapOf(
                    "success" to false,
                    "error" to "No location fix available. Location may be turned off in system settings, or no provider has a signal yet.",
                )

            val includeAddress = (args["include_address"] as? Boolean)
                ?: (args["include_address"]?.toString()?.equals("true", ignoreCase = true) != false)
            val address = if (includeAddress) reverseGeocode(context, location.latitude, location.longitude) else null

            return buildMap {
                put("success", true)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy_meters", location.accuracy.toDouble())
                put("provider", location.provider ?: "unknown")
                put("fix_age_seconds", (System.currentTimeMillis() - location.time) / 1000)
                put("maps_url", "https://maps.google.com/?q=${location.latitude},${location.longitude}")
                address?.let { put("address", it) }
            }
        }
    }

    /**
     * The freshest last-known fix when it is recent enough, else one bounded
     * update request. GPS first for accuracy, then network, then passive.
     */
    @SuppressLint("MissingPermission")
    private suspend fun bestAvailableFix(manager: LocationManager, timeoutSeconds: Long): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val known = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        val fresh = known.filter { System.currentTimeMillis() - it.time <= STALE_FIX_MS }
        fresh.minByOrNull { it.accuracy }?.let { return it }

        for (provider in providers) {
            val update = requestSingleUpdate(manager, provider, timeoutSeconds)
            if (update != null) return update
        }
        // Providers may all be unusable right now; the stale fixes are still better
        // than nothing, so report the most accurate one we have.
        return known.minByOrNull { it.accuracy }
    }

    /** One location update from [provider], or null on timeout/failure. */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(manager: LocationManager, provider: String, timeoutSeconds: Long): Location? = withTimeoutOrNull(timeoutSeconds * 1000) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val executor = java.util.concurrent.Executor { runnable -> runnable.run() }
                manager.getCurrentLocation(provider, null, executor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            suspendCancellableCoroutine<Location?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                try {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }
    }

    /** Best-effort street address; null when the geocoder is unavailable or offline. */
    private suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val results: List<Address>? = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            results?.firstOrNull()?.getAddressLine(0)
        }.getOrNull()
    }
}
