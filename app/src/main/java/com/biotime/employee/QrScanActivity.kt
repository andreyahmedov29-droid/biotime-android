package com.biotime.employee

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
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
 * нужно». Водитель видит прогресс ВО ВРЕМЯ самой камеры, а не только между сканами.
 *
 * Сканирует ОДИН QR за запуск и возвращает результат через setResult + finish
 * (стандартный поток Activity). Веб-цикл (qrScanCallback) остаётся прежним: после
 * скана веб показывает оверлей-прогресс (Путь 1), затем снова открывает этот сканер
 * с обновлённым счётчиком.
 *
 * Передаваемые параметры (Intent extra):
 *   EXTRA_ACTION  — "load" | "unload";
 *   EXTRA_CLIENT  — имя клиента для отображения;
 *   EXTRA_DONE    — сколько уже отсканировано;
 *   EXTRA_NEED    — сколько всего нужно.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "biotime.scan.action"
        const val EXTRA_CLIENT = "biotime.scan.client"
        const val EXTRA_DONE = "biotime.scan.done"
        const val EXTRA_NEED = "biotime.scan.need"

        // Результат (setResult codes/data):
        const val RESULT_CODE = 0x51
        const val RESULT_OK_EXTRA = "biotime.scan.result.code"
        const val RESULT_CANCELLED_EXTRA = "biotime.scan.result.cancelled"
    }

    private var barcodeView: DecoratedBarcodeView? = null
    private var consumedCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = intent.getStringExtra(EXTRA_CLIENT) ?: "Клиент не выбран"
        val done = intent.getIntExtra(EXTRA_DONE, 0).coerceAtLeast(0)
        val need = intent.getIntExtra(EXTRA_NEED, 0).coerceAtLeast(0)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: "load"

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
        setContentView(root)

        // Декодируем каждый появляющийся QR; после первого валидного — возвращаем.
        barcodeView?.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text ?: return
                // Защита от повторной отправки одного и того же кадра.
                if (text == consumedCode) return
                consumedCode = text
                finishWithCode(text)
            }

            override fun possibleResultPoints(resultPoints: List<com.google.zxing.ResultPoint>?) {
                // ignore
            }
        })
    }

    private fun finishWithCode(code: String) {
        val data = Intent().apply {
            putExtra(RESULT_OK_EXTRA, code)
        }
        setResult(RESULT_CODE, data)
        finish()
    }

    override fun onResume() {
        super.onResume()
        barcodeView?.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeView = null
    }

    override fun onBackPressed() {
        val data = Intent().apply {
            putExtra(RESULT_CANCELLED_EXTRA, true)
        }
        setResult(RESULT_CODE, data)
        super.onBackPressed()
    }
}
