package ru.wayfinder.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Проверка готовности приложения к сбору данных. По каждому требованию говорит,
 * выполнено ли оно, и даёт Intent, который ведёт пользователя точно в нужную
 * системную настройку. Приложение НЕ может включить гео/батарею само (Android это
 * запрещает намеренно) — поэтому ведём в один тап туда, где это включает человек.
 */
object Readiness {

    data class Item(
        val title: String,
        val ok: Boolean,
        val hint: String,          // что делать, если не ок
        val fix: Intent?           // куда отправить (null — чинится через запрос разрешений)
    )

    const val FIX_PERMISSIONS = "permissions"   // особый случай: перезапросить разрешения

    fun check(ctx: Context): List<Item> {
        val items = mutableListOf<Item>()

        // 1) Разрешения: геолокация + телефон (и уведомления на 13+).
        val permsOk = hasLocation(ctx) && hasPhone(ctx) && hasNotif(ctx)
        items.add(
            Item(
                "Разрешения (гео, телефон)",
                permsOk,
                "Выдай доступ к местоположению и телефону",
                null // особый случай — перезапрос в активити
            )
        )

        // 2) Геолокация включена системно (не просто разрешение, а сам GPS).
        val locOn = isLocationOn(ctx)
        items.add(
            Item(
                "Геолокация включена",
                locOn,
                "Включи геолокацию в настройках устройства",
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            )
        )

        // 3) Без оптимизации батареи — чтобы фоновый сбор не усыпляли.
        val battOk = ignoresBattery(ctx)
        items.add(
            Item(
                "Батарея: без ограничений",
                battOk,
                "Разреши работу в фоне без энергосбережения",
                batteryIntent(ctx)
            )
        )

        return items
    }

    // ── проверки ──────────────────────────────────────────────────────────────

    fun hasLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPhone(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotif(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        else true

    private fun isLocationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
             lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun ignoresBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ── интенты для починки ─────────────────────────────────────────────────────

    @Suppress("BatteryLife")
    private fun batteryIntent(ctx: Context): Intent =
        // Прямой запрос-диалог «разрешить работу без ограничений».
        // Требует разрешение REQUEST_IGNORE_BATTERY_OPTIMIZATIONS в манифесте.
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    /** Экран настроек приложения — фолбэк, если разрешение отклонили «навсегда». */
    fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }
}
