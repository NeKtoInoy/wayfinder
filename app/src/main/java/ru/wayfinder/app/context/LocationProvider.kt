package ru.wayfinder.app.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Лёгкий провайдер координат без Google Play Services (меньше зависимостей).
 * Берём последнюю известную позицию из системного LocationManager.
 * Нет разрешения или фикса — возвращаем null, замер всё равно состоится.
 */
class LocationProvider(private val ctx: Context) {

    @SuppressLint("MissingPermission")
    fun lastKnown(): Pair<Double?, Double?> {
        if (!hasLocationPermission()) return null to null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null to null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
                if (loc != null && (best == null || loc.time > best!!.time)) best = loc
            }
            best?.let { it.latitude to it.longitude } ?: (null to null)
        } catch (_: Exception) { null to null }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
