package ru.wayfinder.app.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ru.wayfinder.app.data.NetCtx
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            localIp = localIpv4(),
            vpnActive = isVpnActive(cm)
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
        if (tm == null) return Triple(null, null, null)
        return try {
            // На Android 10+ allCellInfo отдаёт устаревший/пустой список, пока не
            // запросишь свежие данные явно. Делаем это с коротким таймаутом.
            val list = freshCellInfo(tm) ?: tm.allCellInfo ?: return Triple(null, null, null)
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

    // Принудительно запрашивает свежий список сот (API 29+). Без этого на новых
    // Android сота и сигнал часто приходят пустыми. До API 29 возвращаем null —
    // там allCellInfo и так свежий.
    @SuppressLint("MissingPermission")
    private fun freshCellInfo(tm: TelephonyManager): List<CellInfo>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val latch = CountDownLatch(1)
            var out: List<CellInfo>? = null
            val exec = Executors.newSingleThreadExecutor()
            tm.requestCellInfoUpdate(exec, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    out = cellInfo
                    latch.countDown()
                }
                override fun onError(errorCode: Int, detail: Throwable?) {
                    latch.countDown()
                }
            })
            latch.await(3, TimeUnit.SECONDS)
            exec.shutdownNow()
            out
        } catch (_: Exception) { null }
    }

    // Включён ли VPN. Через туннель замеры мерят работу VPN, а не самой сети —
    // поэтому помечаем такие снимки, чтобы их можно было отфильтровать при анализе.
    private fun isVpnActive(cm: ConnectivityManager?): Boolean {
        cm ?: return false
        return try {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) { false }
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

    // Данные соты на Android 10+ требуют И телефонного разрешения, И геолокации.
    private fun hasPhonePermission(): Boolean {
        val phone = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return phone && loc
    }

    @Suppress("unused")
    private fun sdk() = Build.VERSION.SDK_INT
}
