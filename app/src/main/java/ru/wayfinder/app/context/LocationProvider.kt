package ru.wayfinder.app.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Координаты без Google Play Services (меньше зависимостей).
 * Сначала пробуем получить СВЕЖИЙ фикс с коротким таймаутом, и только если не вышло —
 * берём последнюю известную позицию. Так в логе не будет пустого гео, когда GPS
 * ещё не прогрелся, но сеть/пассивный провайдер уже что-то знают.
 * Нет разрешения или фикса — возвращаем null, замер всё равно состоится.
 */
class LocationProvider(private val ctx: Context) {

    @SuppressLint("MissingPermission")
    fun fix(timeoutMs: Long = 6000): Pair<Double?, Double?> {
        if (!hasLocationPermission()) return null to null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null to null

        // 1) Пытаемся получить свежий фикс в течение таймаута.
        val fresh = requestFresh(lm, timeoutMs)
        if (fresh != null) return fresh.latitude to fresh.longitude

        // 2) Фолбэк — последняя известная позиция из любого провайдера.
        return lastKnown(lm)
    }

    @SuppressLint("MissingPermission")
    private fun requestFresh(lm: LocationManager, timeoutMs: Long): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val latch = CountDownLatch(1)
        var result: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("deprecated API")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        return try {
            // Слушатель требует Looper — отдаём главный.
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            lm.removeUpdates(listener)
            result
        } catch (_: Exception) {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Pair<Double?, Double?> {
        return try {
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
