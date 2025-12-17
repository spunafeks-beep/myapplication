package com.example.myapplication

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI
        videoLayout = findViewById(R.id.videoLayout)
        tvStatus = findViewById(R.id.tvStatus)

        val etIpAddress = findViewById<EditText>(R.id.etIpAddress)
        val btnConnectVideo = findViewById<Button>(R.id.btnConnectCamera)
        val btnConnectUsb = findViewById<Button>(R.id.btnConnectUsb)
        val btnSnapshot = findViewById<Button>(R.id.btnSnapshot)

        // --- ДЖОЙСТИК ---
        // Убедись, что в layout используется наш кастомный класс
        val joystick = findViewById<JoystickView>(R.id.joystickView)

        // Логика кнопок
        btnConnectUsb.setOnClickListener { connectUsb() }
        btnConnectVideo.setOnClickListener {
            val url = etIpAddress.text.toString()
            startVideo(url)
        }
        btnSnapshot.setOnClickListener { takeSnapshot() }

        // Логика Джойстика
        setupJoystick(joystick)
    }

    // --- УПРАВЛЕНИЕ ДВИГАТЕЛЯМИ (ДЖОЙСТИК) ---
    private fun setupJoystick(joystick: JoystickView) {
        joystick.onMoveListener = { x, y ->
            // x и y приходят в диапазоне -100..100

            // Микширование для танкового управления (дифференциальный привод)
            // При движении вперед (y > 0), оба мотора крутятся вперед.
            // При повороте (x != 0), добавляем разницу скоростей.

            var leftMotor = y + x
            var rightMotor = y - x

            // Ограничиваем значения, чтобы не выйти за пределы -100..100
            leftMotor = leftMotor.coerceIn(-100, 100)
            rightMotor = rightMotor.coerceIn(-100, 100)

            // Отправляем данные на ESP
            // Используем один вызов write, если ESP умеет парсить сразу две команды,
            // или два вызова, как у тебя было раньше.
            sendToEsp("M1:$leftMotor\n")
            sendToEsp("M2:$rightMotor\n")
        }
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
        val driver = availableDrivers[0]
        val connection = usbManager!!.openDevice(driver.device)
        if (connection == null) {
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager!!.requestPermission(driver.device, pendingIntent)
            return
        }
        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort!!.open(connection)
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
            args.add("-vvv")
            args.add("--rtsp-tcp")
            args.add("--network-caching=150")

            libVlc = LibVLC(this, args)
            mediaPlayer = MediaPlayer(libVlc)
            mediaPlayer!!.attachViews(videoLayout, null, false, false)
            val media = Media(libVlc, url.toUri())

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
        val videoWidth = videoLayout.width
        val videoHeight = videoLayout.height

        if (videoWidth == 0 || videoHeight == 0) {
            Toast.makeText(this, "Video view not ready.", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)

        try {
            videoLayout.draw(Canvas(bitmap))
        } catch (e: Exception) {
            Log.e("SNAPSHOT", "Error drawing to bitmap: ${e.message}", e)
            Toast.makeText(this, "Failed to capture frame.", Toast.LENGTH_SHORT).show()
            return
        }

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
                            "Скриншот сохранён",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
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