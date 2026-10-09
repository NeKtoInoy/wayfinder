package ru.wayfinder.app.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ru.wayfinder.app.data.NetCtx
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Сбор контекста сети на момент замера. Всё, что требует разрешений
 * (гео, подробности соты), берётся мягко: нет разрешения — поле остаётся null,
 * приложение не падает. Разрешения запрашивает UI.
 *
 * Отдельно и важно: достаём IP DNS-резолвера оператора — именно его опрашивает
 * DnsProbe, потому что под белыми списками публичные 8.8.8.8/1.1.1.1 недоступны.
 */
class NetworkContext(private val ctx: Context) {

    @SuppressLint("MissingPermission")
    fun collect(lat: Double?, lon: Double?): NetCtx {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val link: LinkProperties? = net?.let { cm.getLinkProperties(it) }

        val networkType = describeNetwork(caps, tm)
        val resolver = firstResolver(link)
        val operator = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        val mccMnc = tm?.networkOperator?.takeIf { it.isNotBlank() }

        var signal: Int? = null
        var cellId: String? = null
        var lac: String? = null
        if (hasPhonePermission()) {
            val cell = readCell(tm)
            signal = cell.first
            cellId = cell.second
            lac = cell.third
        }

        return NetCtx(
            networkType = networkType,
            operator = operator,
            mccMnc = mccMnc,
            signalDbm = signal,
            cellId = cellId,
            lac = lac,
            lat = lat,
            lon = lon,
            resolver = resolver,
            localIp = localIpv4()
        )
    }

    private fun describeNetwork(caps: NetworkCapabilities?, tm: TelephonyManager?): String {
        if (caps == null) return "нет сети"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val gen = if (hasPhonePermission()) cellGen(tm) else ""
            return if (gen.isNotEmpty()) "mobile $gen" else "mobile"
        }
        return "прочее"
    }

    @SuppressLint("MissingPermission")
    private fun cellGen(tm: TelephonyManager?): String = try {
        when (tm?.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> ""
        }
    } catch (_: Exception) { "" }

    // Возвращает (signalDbm, cellId, lac) из первой подходящей соты.
    @SuppressLint("MissingPermission")
    private fun readCell(tm: TelephonyManager?): Triple<Int?, String?, String?> {
        return try {
            val list = tm?.allCellInfo ?: return Triple(null, null, null)
            for (info in list) {
                when (info) {
                    is CellInfoLte -> return Triple(
                        info.cellSignalStrength.dbm,
                        info.cellIdentity.ci.toString(),
                        info.cellIdentity.tac.toString()
                    )
                    is CellInfoNr -> return Triple(info.cellSignalStrength.dbm, null, null)
                    is CellInfoWcdma -> return Triple(
                        info.cellSignalStrength.dbm,
                        info.cellIdentity.cid.toString(),
                        info.cellIdentity.lac.toString()
                    )
                    is CellInfoGsm -> return Triple(
                        info.cellSignalStrength.dbm,
                        info.cellIdentity.cid.toString(),
                        info.cellIdentity.lac.toString()
                    )
                }
            }
            Triple(null, null, null)
        } catch (_: Exception) { Triple(null, null, null) }
    }

    private fun firstResolver(link: LinkProperties?): String? {
        val dns = link?.dnsServers ?: return null
        // Предпочитаем IPv4-резолвер — с ним DnsProbe работает проще.
        val v4 = dns.firstOrNull { it is Inet4Address }
        return (v4 ?: dns.firstOrNull())?.hostAddress
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (_: Exception) { null }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("unused")
    private fun sdk() = Build.VERSION.SDK_INT
}
