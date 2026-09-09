package com.biotime.employee

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Кастомный НЕ-полноэкранный сканер QR (Путь 2).
 *
 * Камера-превью (ScannerView из zxing-android-embedded) занимает верхнюю часть
 * экрана, а в нижней — панель с именем клиента и живым счётчиком «отсканировано /
 * нужно». Камера НЕ закрывается после каждого скана — считывание идёт непрерывно,
 * а счётчик в нижней панели растёт на месте. Каждый отсканированный код тут же
 * уходит в веб через колбэк (webSignal), веб отмечает место на сервере.
 *
 * Сканирует непрерывно, пока пользователь не закроет окно. Закрыть можно
 * заметной кнопкой-крестиком в правом верхнем углу либо системной кнопкой
 * «Назад». Закрывается Activity с результатом: последний (или отсутствующий)
 * код + счётчик done/need, чтобы веб мог дозапросить оставшиеся места. Для
 * возврата каждого кода в веб используется сигнальный мост (передаётся из
 * MainActivity перед запуском).
 *
 * Передаваемые параметры (Intent extra):
 *   EXTRA_ACTION  — "load" | "unload";
 *   EXTRA_CLIENT  — имя клиента для отображения;
 *   EXTRA_DONE    — сколько уже отсканировано;
 *   EXTRA_NEED    — сколько всего нужно.
 *   EXTRA_CALLBACK — имя веб-функции-колбэка (window[callback](payload)).
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "biotime.scan.action"
        const val EXTRA_CLIENT = "biotime.scan.client"
        const val EXTRA_DONE = "biotime.scan.done"
        const val EXTRA_NEED = "biotime.scan.need"
        const val EXTRA_CALLBACK = "biotime.scan.callback"

        // Результат (setResult codes/data):
        const val RESULT_CODE = 0x51
        const val RESULT_OK_EXTRA = "biotime.scan.result.code"
        const val RESULT_CANCELLED_EXTRA = "biotime.scan.result.cancelled"

        // Мост для отправки отсканированного кода в веб. Ставится MainActivity
        // перед запуском Activity (не может быть передан через Intent). Если мост
        // не установлен — Activity закрывается после первого скана (старое поведение).
        @Volatile var webSignal: ((code: String, action: String, done: Int, need: Int) -> Unit)? = null

        // Текущая активная Activity сканера — чтобы веб (через MainActivity) мог
        // закрыть камеру, когда сервер подтвердит завершение выгрузки мест.
        @Volatile var current: QrScanActivity? = null
    }

    private var barcodeView: DecoratedBarcodeView? = null
    private var consumedCode: String? = null
    private var done = 0
    private var need = 0
    private var action = "load"
    private var callback: String = "qrScanCallback"
    private var counterText: TextView? = null
    // Коды мест, уже засчитанных в текущей сессии сканирования. Камера
    // (decodeContinuous) может отдавать ОДИН И ТОТ ЖЕ QR несколько раз подряд
    // (разные кадры/детекции = «пики»). Счётчик done растёт ТОЛЬКО на уникальные
    // места, чтобы не видеть «11 / 5» при пяти реальных местах.
    private val seen = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = intent.getStringExtra(EXTRA_CLIENT) ?: "Клиент не выбран"
        done = intent.getIntExtra(EXTRA_DONE, 0).coerceAtLeast(0)
        need = intent.getIntExtra(EXTRA_NEED, 0).coerceAtLeast(0)
        action = intent.getStringExtra(EXTRA_ACTION) ?: "load"
        callback = intent.getStringExtra(EXTRA_CALLBACK) ?: callback

        // Корневой вертикальный layout: камера (вес 1) + панель прогресса внизу.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E13.toInt())
        }

        barcodeView = DecoratedBarcodeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setStatusText("")
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        TextView(this).apply {
            text = client
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            panel.addView(this)
        }
        TextView(this).apply {
            text = "$done / $need"
            textSize = 44f
            setTextColor(0xFFFFAB38.toInt())
            counterText = this
            panel.addView(this)
        }
        TextView(this).apply {
            text = "Наведите камеру на QR-код этикетки (${if (action == "unload") "выгрузка" else "погрузка"})"
            textSize = 13f
            setTextColor(0xFF9CA3AF.toInt())
            panel.addView(this)
        }

        // barcodeView уже получил layoutParams (ширина MATCH_PARENT, высота 0, вес 1f)
        // в блоке .apply выше. Повторная передача LayoutParams здесь перезаписала бы их
        // (высота 0 без веса — камера схлопнулась бы), поэтому добавляем без параметров.
        root.addView(barcodeView)
        root.addView(panel)

        // Контейнер-обёртка, поверх камеры располагаем заметный крестик «выхода».
        val container = FrameLayout(this)
        container.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Крестик закрытия: крупный, контрастный, поверх камеры в правом верхнем углу.
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            // Фон-кружок: тёмно-синий полупрозрачный с тонкой светлой рамкой — виден
            // на любом кадре камеры.
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xB3141822.toInt())
                setStroke(dp(2f).toInt(), 0x66FFFFFF.toInt())
            }
            background = bg
            val size = dp(56f).toInt()
            val lp = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(18f).toInt(), dp(18f).toInt(), 0)
            }
            layoutParams = lp
            setOnClickListener { closeSelf() }
        }
        container.addView(closeBtn)

        setContentView(container)

        // Декодируем каждый появляющийся QR НЕПРЕРЫВНО. Камеру не закрываем
        // автоматически (даже когда счётчик достиг need): каждый код уходит в веб,
        // а закрытие — только по крестику, «Назад» или при отсутствии моста в веб.
        barcodeView?.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text ?: return
                // Защита от повторной отправки одного и того же кадра.
                if (text == consumedCode) return
                consumedCode = text
                handleScanned(text)
            }

            override fun possibleResultPoints(resultPoints: List<com.google.zxing.ResultPoint>?) {
                // ignore
            }
        })
    }

    private fun handleScanned(code: String) {
        val signal = webSignal
        if (signal == null) {
            // Нет моста в веб — закрываемся с результатом по-старому (один QR).
            finishWithCode(code)
            return
        }
        // Повторный «пик» уже засчитанного места — игнорируем: счётчик и журнал
        // не должны учитывать дублирующие детекции одного кода.
        if (!seen.add(code)) return
        done++
        runOnUiThread {
            counterText?.text = "$done / $need"
        }
        // Отправляем код в веб (сервер отметит место). Веб сам решит, когда хватит.
        runOnUiThread {
            signal(code, action, done, need)
        }
    }

    private fun finishWithCode(code: String) {
        val data = Intent().apply {
            putExtra(RESULT_OK_EXTRA, code)
        }
        data.putExtra("biotime.scan.done", done)
        data.putExtra("biotime.scan.need", need)
        setResult(RESULT_CODE, data)
        finish()
    }

    /** Закрытие окна сканера по крестику — как системная кнопка «Назад» (отмена). */
    private fun closeSelf() {
        onBackPressed()
    }

    private fun dp(v: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    }

    override fun onResume() {
        super.onResume()
        current = this
        barcodeView?.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current === this) current = null
        barcodeView = null
    }

    /** Закрытие камеры по команде из веба (когда сервер засчитал все места). */
    fun closeFromWeb() {
        runOnUiThread { finishWithCode(consumedCode ?: "") }
    }

    override fun onBackPressed() {
        val data = Intent().apply {
            putExtra(RESULT_CANCELLED_EXTRA, true)
        }
        setResult(RESULT_CODE, data)
        super.onBackPressed()
    }
}
