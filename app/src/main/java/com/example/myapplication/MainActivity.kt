package com.example.myapplication

import android.content.Context
import android.os.Build
import android.app.PendingIntent
import android.content.BroadcastReceiver

import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView

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

// КОНСТАНТЫ ВЫНОСИМ ЗА ПРЕДЕЛЫ КЛАССА
private const val ACTION_USB_PERMISSION = "com.example.myapplication.USB_PERMISSION"

class MainActivity : AppCompatActivity() {

    private var usbSerialPort: UsbSerialPort? = null
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout

    private lateinit var tvStatusUsb: TextView
    private lateinit var tvStatusVideo: TextView
    private lateinit var tvMotors: TextView
    private lateinit var tvFps: TextView

    private lateinit var prefs: SharedPreferences
    private val PREF_URL_KEY = "last_rtsp_url"
    private val TAG = "JOYSTICK_DEBUG"
    private var lastFpsUpdateTime = 0L

    // Приемник ответа от системного окна разрешений USB
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                synchronized(this) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        connectUsbAuto() // Если нажали "ДА", сразу подключаемся
                    } else {
                        tvStatusUsb.text = "USB: PERMISSION DENIED"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        tvStatusUsb = findViewById(R.id.tvStatusUsb)
        tvStatusVideo = findViewById(R.id.tvStatusVideo)
        tvMotors = findViewById(R.id.tvMotors)
        tvFps = findViewById(R.id.tvFps)
        videoLayout = findViewById(R.id.videoLayout)
        val joystick = findViewById<JoystickView>(R.id.joystickView)

        prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // Регистрируем приемник разрешений
        val filter = IntentFilter(ACTION_USB_PERMISSION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Для Android 13 (API 33) и выше используем флаг
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Для старых версий оставляем как было
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        }

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
        input.setText(prefs.getString(PREF_URL_KEY, "rtsp://192.168.1.100:554"))

        AlertDialog.Builder(this)
            .setTitle("Подключение")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("ОК") { _, _ ->
                val url = input.text.toString()
                prefs.edit().putString(PREF_URL_KEY, url).apply()
                startVideo(url)
                hideSystemUI()
            }
            .show()
    }

    private fun connectUsbAuto() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            tvStatusUsb.text = "USB: NOT FOUND"
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            // ИСПРАВЛЕНИЕ ТУТ:
            // Используем FLAG_MUTABLE (так как системе нужно дописать данные в Intent),
            // но делаем Intent "явным", указывая пакет вашего приложения.
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName) // Это делает Intent "явным" (explicit)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE // Для USB разрешения часто нужен именно MUTABLE
            } else {
                0
            }

            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)

            tvStatusUsb.text = "USB: WAIT PERMISSION..."
            return
        }

        try {
            val connection = usbManager.openDevice(device)
            usbSerialPort = driver.ports[0]
            usbSerialPort!!.open(connection)
            usbSerialPort!!.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            tvStatusUsb.text = "USB: CONNECTED"
        } catch (e: Exception) {
            tvStatusUsb.text = "USB: ERROR"
        }
    }

    private fun startVideo(url: String) {
        try {
            libVlc = LibVLC(this, arrayListOf("-vvv", "--rtsp-tcp", "--network-caching=300"))
            mediaPlayer = MediaPlayer(libVlc)
            mediaPlayer!!.attachViews(videoLayout, null, false, false)

            mediaPlayer!!.setEventListener { event ->
                runOnUiThread {
                    when (event.type) {
                        MediaPlayer.Event.Playing -> tvStatusVideo.text = "VIDEO: PLAYING"
                        MediaPlayer.Event.TimeChanged -> {
                            val now = System.currentTimeMillis()
                            if (now - lastFpsUpdateTime > 1000) {
                                val v = mediaPlayer?.currentVideoTrack
                                tvFps.text = "RES: ${v?.width ?: 0}x${v?.height ?: 0}"
                                lastFpsUpdateTime = now
                            }
                        }
                    }
                }
            }

            val media = Media(libVlc, url.toUri())
            mediaPlayer!!.media = media
            media.release()
            mediaPlayer!!.play()
        } catch (e: Exception) {
            Log.e(TAG, "VLC Error: ${e.message}")
        }
    }

    private fun setupJoystick(joystick: JoystickView) {
        joystick.onMoveListener = { x, y ->
            // Финальный микшер (используем X как газ, как ты определил)
            val throttle = x
            val steer = if (Math.abs(y) < 15) 0 else y

            val left = (throttle + steer).coerceIn(-100, 100)
            val right = (throttle - steer).coerceIn(-100, 100)

            runOnUiThread { tvMotors.text = "MOTORS: L:$left R:$right" }
            sendToEsp("S:$left:$right\n")
        }
    }

    private fun sendToEsp(command: String) {
        if (usbSerialPort?.isOpen == true) {
            try {
                usbSerialPort!!.write(command.toByteArray(), 100)
            } catch (e: IOException) { e.printStackTrace() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver) // Обязательно отключаем слушатель
        mediaPlayer?.release()
        libVlc?.release()
        try { usbSerialPort?.close() } catch (e: Exception) {}
    }
}