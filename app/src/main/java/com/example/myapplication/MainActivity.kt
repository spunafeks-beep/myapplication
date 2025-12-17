package com.example.myapplication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var usbSerialPort: UsbSerialPort? = null
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout

    // Переменные телеметрии
    private lateinit var tvStatusUsb: TextView
    private lateinit var tvStatusVideo: TextView
    private lateinit var tvMotors: TextView
    private lateinit var tvFps: TextView

    private lateinit var prefs: SharedPreferences
    private val PREF_URL_KEY = "last_rtsp_url"
    private val TAG = "JOYSTICK_DEBUG"

    private var frameCounter = 0
    private var lastFpsUpdateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        // Инициализация UI элементов
        videoLayout = findViewById(R.id.videoLayout)
        tvStatusUsb = findViewById(R.id.tvStatusUsb)
        tvStatusVideo = findViewById(R.id.tvStatusVideo)
        tvMotors = findViewById(R.id.tvMotors)
        tvFps = findViewById(R.id.tvFps)
        val joystick = findViewById<JoystickView>(R.id.joystickView)

        prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        setupJoystick(joystick)
        connectUsbAuto()
        showUrlInputDialog()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showUrlInputDialog() {
        val input = EditText(this)
        val lastUrl = prefs.getString(PREF_URL_KEY, "rtsp://192.168.1.100:554")
        input.setText(lastUrl)

        AlertDialog.Builder(this)
            .setTitle("Подключение")
            .setMessage("Введите RTSP адрес:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("ОК") { _, _ ->
                val url = input.text.toString()
                if (url.isNotEmpty()) {
                    prefs.edit().putString(PREF_URL_KEY, url).apply()
                    startVideo(url)
                    hideSystemUI()
                }
            }
            .setNegativeButton("Выход") { _, _ -> finish() }
            .show()
    }

    private fun startVideo(url: String) {
        try {
            // Освобождаем ресурсы, если видео уже было запущено
            mediaPlayer?.stop()
            mediaPlayer?.release()
            libVlc?.release()

            val args = arrayListOf("-vvv", "--rtsp-tcp", "--network-caching=150")
            libVlc = LibVLC(this, args)
            mediaPlayer = MediaPlayer(libVlc)
            mediaPlayer!!.attachViews(videoLayout, null, false, false)

            // Настройка логов и событий видео
            mediaPlayer!!.setEventListener { event ->
                runOnUiThread {
                    when (event.type) {
                        MediaPlayer.Event.Playing -> tvStatusVideo.text = "VIDEO: PLAYING"
                        MediaPlayer.Event.Stopped -> tvStatusVideo.text = "VIDEO: STOPPED"
                        MediaPlayer.Event.EncounteredError -> tvStatusVideo.text = "VIDEO: ERROR"
                        MediaPlayer.Event.TimeChanged -> {
                            // Каждую секунду обновляем интерфейс
                            val now = System.currentTimeMillis()
                            if (now - lastFpsUpdateTime > 1000) {
                                // Так как прямого доступа к FPS в некоторых версиях нет,
                                // выводим разрешение видео для контроля связи
                                val width = mediaPlayer?.currentVideoTrack?.width ?: 0
                                val height = mediaPlayer?.currentVideoTrack?.height ?: 0
                                tvFps.text = "RES: ${width}x${height}"
                                lastFpsUpdateTime = now
                            }
                        }
                    }
                }
            }

            val media = Media(libVlc, url.toUri())
            media.addOption(":network-caching=150")
            media.addOption(":clock-jitter=0")

            mediaPlayer!!.media = media
            media.release()
            mediaPlayer!!.play()

        } catch (e: Exception) {
            Log.e(TAG, "VLC Error: ${e.message}")
            Toast.makeText(this, "VLC Crash avoided!", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectUsbAuto() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            tvStatusUsb.text = "USB: NOT FOUND"
            return
        }

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)

        if (connection == null) {
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent("com.example.myapplication.USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(driver.device, pendingIntent)
            tvStatusUsb.text = "USB: PERMISSION?"
            return
        }

        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort!!.open(connection)
            usbSerialPort!!.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            tvStatusUsb.text = "USB: CONNECTED"
        } catch (e: IOException) {
            tvStatusUsb.text = "USB: ERROR"
        }
    }

    private fun setupJoystick(joystick: JoystickView) {
        joystick.onMoveListener = { x, y ->
            // 1. Поменяем оси: раз ВВЕРХ дает один мотор, значит для нас ВВЕРХ это X
            // Пробуем: throttle (газ) берем из X, а steer (руль) из Y
            val throttle = x
            val steer = y

            // 2. Мертвая зона
            val filteredSteer = if (Math.abs(steer) < 15) 0 else steer

            // 3. Танковый микшер
            var left = throttle + filteredSteer
            var right = throttle - filteredSteer

            val finalLeft = left.coerceIn(-100, 100)
            val finalRight = right.coerceIn(-100, 100)

            runOnUiThread {
                // Выводим данные для отладки прямо на экран
                tvMotors.text = "MOTORS: L:$finalLeft R:$finalRight"
                // В лог пишем сырые данные джойстика
                Log.d(TAG, "JOYSTICK RAW -> X: $x, Y: $y")
            }

            // Отправка команды S:L:R\n
            sendToEsp("S:$finalLeft:$finalRight\n")
        }
    }

    private fun sendToEsp(command: String) {
        if (usbSerialPort != null && usbSerialPort!!.isOpen) {
            try {
                usbSerialPort!!.write(command.toByteArray(), 100)
            } catch (e: IOException) {
                runOnUiThread { tvStatusUsb.text = "USB: LOST" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        libVlc?.release()
        try { usbSerialPort?.close() } catch (e: Exception) {}
    }
}