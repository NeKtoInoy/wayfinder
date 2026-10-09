package ru.wayfinder.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.wayfinder.app.R
import ru.wayfinder.app.data.LogStore
import ru.wayfinder.app.runner.ProbeRunner
import ru.wayfinder.app.service.SamplerService

/**
 * Тонкий слой UI поверх раннера и сервиса. Три действия:
 *  — «Снять срез» (разовый замер),
 *  — тумблер фонового сбора (каждые 3 мин),
 *  — «Экспорт лога» (отдать JSON через шаринг).
 * Вся логика — в runner/service/probe, тут только кнопки и вывод.
 */
class MainActivity : AppCompatActivity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: LogStore
    private lateinit var runner: ProbeRunner

    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusContainer: LinearLayout
    private var sampling = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }  // после ответа — перерисовать блок готовности

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = LogStore.get(applicationContext)
        runner = ProbeRunner(applicationContext)

        out = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)
        statusContainer = findViewById(R.id.statusContainer)
        val btnOnce = findViewById<Button>(R.id.btnOnce)
        val btnBg = findViewById<Button>(R.id.btnBackground)
        val btnExport = findViewById<Button>(R.id.btnExport)
        val btnClear = findViewById<Button>(R.id.btnClear)

        requestPermissions()

        btnOnce.setOnClickListener { runOnce() }

        btnBg.setOnClickListener {
            sampling = !sampling
            if (sampling) {
                // Перед долгим фоновым сбором стоит снять ограничения батареи.
                if (!Readiness.ignoresBattery(this)) {
                    append("⚠ Для фона лучше снять ограничения батареи — кнопка «Исправить» вверху.")
                }
                SamplerService.start(this)
                btnBg.text = "Остановить фон. сбор"
                append("Фоновый сбор запущен (каждые 3 мин).")
            } else {
                SamplerService.stop(this)
                btnBg.text = "Запустить фон. сбор"
                append("Фоновый сбор остановлен.")
            }
        }

        btnExport.setOnClickListener { exportLog() }

        btnClear.setOnClickListener {
            store.clear()
            append("Лог очищен.")
        }

        refreshStatus()
        append("Готов. Замеров в логе: ${store.count()}")
    }

    override fun onResume() {
        super.onResume()
        // Пользователь мог вернуться из системных настроек — обновим галочки.
        refreshStatus()
    }

    /** Перерисовывает блок готовности: строка на каждое требование + кнопка «Исправить». */
    private fun refreshStatus() {
        statusContainer.removeAllViews()
        for (item in Readiness.check(this)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(this).apply {
                text = "${if (item.ok) "✓" else "✗"}  ${item.title}"
                textSize = 13f
                setTextColor(if (item.ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(label)
            if (!item.ok) {
                val fixBtn = Button(this).apply {
                    text = "Исправить"
                    textSize = 11f
                    setOnClickListener { fix(item) }
                }
                row.addView(fixBtn)
            }
            statusContainer.addView(row)
        }
    }

    private fun fix(item: Readiness.Item) {
        if (item.fix == null) {
            // Разрешения: сначала пробуем перезапросить; если отклонили «навсегда»,
            // система просто не покажет диалог — тогда ведём в настройки приложения.
            requestPermissions()
            return
        }
        try {
            startActivity(item.fix)
        } catch (_: Exception) {
            try { startActivity(Readiness.appDetailsIntent(this)) } catch (_: Exception) {}
        }
    }

    private fun runOnce() {
        val started = timeNow()
        append("\n━━━ РУЧНОЙ СРЕЗ начат $started ━━━")
        // Мягкое предупреждение: гео выключено — координат в замере не будет.
        val warn = Readiness.check(this).firstOrNull { !it.ok }
        if (warn != null) append("  ⚠ ${warn.title}: ${warn.hint} (кнопка «Исправить» вверху)")
        ui.launch {
            val snap = withContext(Dispatchers.IO) {
                runner.runOnce(source = "manual") { p -> ui.launch { append("  $p") } }
            }
            store.add(snap)
            // Коротко покажем ключевые строки
            snap.probes.filter { it.group == "whitelist" && it.kind == "tls" }
                .take(6).forEach { append("  ${it.label} → ${it.outcome} (${it.ms}мс)") }
            append("━━━ РУЧНОЙ СРЕЗ завершён · ${snap.verdict} · зондов ${snap.probes.size} · в логе ${store.count()} ━━━")
        }
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private fun exportLog() {
        if (store.count() == 0) { append("Лог пуст."); return }
        val file = store.exportFile(this)
        val uri: Uri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Отправить лог"))
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun append(line: String) {
        out.append("\n$line")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
