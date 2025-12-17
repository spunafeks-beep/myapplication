package com.example.myapplication // УБЕДИСЬ, ЧТО ИМЯ ПАКЕТА СОВПАДАЕТ С ТВОИМ

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // --- USB Переменные ---
    private var usbManager: UsbManager? = null
    private var usbSerialPort: UsbSerialPort? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.myapplication.USB_PERMISSION"
    }

    // --- Видео Переменные ---
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout

    // --- UI ---
    private lateinit var tvStatus: TextView
    private lateinit var seekBarLeft: SeekBar
    private lateinit var seekBarRight: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI
        videoLayout = findViewById(R.id.videoLayout)
        tvStatus = findViewById(R.id.tvStatus)
        seekBarLeft = findViewById(R.id.seekBarLeft)
        seekBarRight = findViewById(R.id.seekBarRight)

        val etIpAddress = findViewById<EditText>(R.id.etIpAddress)
        val btnConnectVideo = findViewById<Button>(R.id.btnConnectCamera)
        val btnConnectUsb = findViewById<Button>(R.id.btnConnectUsb)
        val btnSnapshot = findViewById<Button>(R.id.btnSnapshot)

        // Логика кнопок
        btnConnectUsb.setOnClickListener { connectUsb() }
        btnConnectVideo.setOnClickListener {
            val url = etIpAddress.text.toString()
            startVideo(url)
        }
        btnSnapshot.setOnClickListener { takeSnapshot() }

        // Логика Ползунков (Motor Control)
        setupJoystick(seekBarLeft, 1) // M1
        setupJoystick(seekBarRight, 2) // M2
    }

    // --- УПРАВЛЕНИЕ ДВИГАТЕЛЯМИ ---
    private fun setupJoystick(seekBar: SeekBar, motorIndex: Int) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                // SeekBar 0..200 -> Motor -100..100
                val speed = progress - 100
                sendToEsp("M$motorIndex:$speed\n")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Пружина: возврат в центр при отпускании
                seekBar?.progress = 100
                sendToEsp("M$motorIndex:0\n")
            }
        })
    }

    private fun sendToEsp(command: String) {
        if (usbSerialPort != null) {
            try {
                usbSerialPort!!.write(command.toByteArray(), 100)
            } catch (e: IOException) {
                runOnUiThread {
                    val errorMessage = getString(R.string.usb_error, e.message)
                    tvStatus.text = errorMessage
                }
            }
        }
    }

    // --- USB ПОДКЛЮЧЕНИЕ ---
    private fun connectUsb() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            tvStatus.setText(R.string.no_usb_device)
            return
        }
        // Берем первое попавшееся устройство (например, CH340)
        val driver = availableDrivers[0]
        val connection = usbManager!!.openDevice(driver.device)
        if (connection == null) {
            // Запрашиваем права, если их нет
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager!!.requestPermission(driver.device, pendingIntent)
            return
        }
        usbSerialPort = driver.ports[0] // Обычно порт 0
        try {
            usbSerialPort!!.open(connection)
            // Настройки: 115200 бод, 8 бит, 1 стоп-бит, без четности
            usbSerialPort!!.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            tvStatus.setText(R.string.usb_connected)
            tvStatus.setTextColor(getColor(R.color.status_ok))
        } catch (e: IOException) {
            val errorMessage = getString(R.string.usb_error, e.message)
            tvStatus.text = errorMessage
            tvStatus.setTextColor(getColor(R.color.status_error))
        }
    }

    // --- ВИДЕО (RTSP) ---
    private fun startVideo(url: String) {
        try {
            val args = arrayListOf<String>()
            args.add("-vvv") // Логи
            args.add("--rtsp-tcp") // Использовать TCP для стабильности
            args.add("--network-caching=150") // Кэш 150мс

            libVlc = LibVLC(this, args)
            mediaPlayer = MediaPlayer(libVlc)
            mediaPlayer!!.attachViews(videoLayout, null, false, false)
            val media = Media(libVlc, url.toUri())

            // Дополнительные опции для низкой задержки
            media.addOption(":network-caching=150")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")

            mediaPlayer!!.media = media
            media.release()
            mediaPlayer!!.play()

            Toast.makeText(this, "Video connecting...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Video Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- СКРИНШОТ ---
    private fun takeSnapshot() {
        if (mediaPlayer == null || libVlc == null) {
            Toast.makeText(this, "Video stream not active.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Получаем размер видеоконтейнера
        val videoWidth = videoLayout.width
        val videoHeight = videoLayout.height

        if (videoWidth == 0 || videoHeight == 0) {
            Toast.makeText(this, "Video view not ready.", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Создаём Bitmap нужного размера
        val bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)

        // 3. Рисуем содержимое видеоLayout в Bitmap
        try {
            videoLayout.draw(Canvas(bitmap))
        } catch (e: Exception) {
            Log.e("SNAPSHOT", "Error drawing to bitmap: ${e.message}", e)
            Toast.makeText(this, "Failed to capture frame.", Toast.LENGTH_SHORT).show()
            return
        }

        // 4. Сохраняем Bitmap в галерею (MediaStore API)
        val displayName = "snapshot_${System.currentTimeMillis()}.jpg"
        val mimeType = "image/jpeg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "TubeExplorer"
                )
            }
        }

        val resolver = contentResolver
        var imageUri: Uri? = null

        try {
            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                resolver.openOutputStream(imageUri).use { outputStream ->
                    if (outputStream != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                        Toast.makeText(
                            this,
                            "Скриншот сохранён: ${Environment.DIRECTORY_PICTURES}/TubeExplorer/$displayName",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, "Не удалось открыть поток для сохранения", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Не удалось создать URI для сохранения", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("SNAPSHOT", "Error saving snapshot: ${e.message}", e)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        libVlc?.release()
        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}