package app.shake.flash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.math.sqrt

/** Double shake toggles the flashlight, even with the screen off. */
class ShakeService : Service(), SensorEventListener {
    private lateinit var sm: SensorManager
    private lateinit var cm: CameraManager
    private var camId: String? = null
    private var torchOn = false
    private var wake: PowerManager.WakeLock? = null
    private var lastShake = 0L
    private var shakeCount = 0
    private var lastToggle = 0L

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == camId) torchOn = enabled
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("shake", "Shake Flash", NotificationManager.IMPORTANCE_MIN)
        )
        val note = Notification.Builder(this, "shake")
            .setContentTitle("Shake Flash is on")
            .setContentText("Shake twice to toggle the flashlight")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, note)
        }

        cm = getSystemService(CameraManager::class.java)
        camId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cm.registerTorchCallback(torchCb, null)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shake:svc").apply { acquire() }

        sm = getSystemService(SensorManager::class.java)
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        cm.unregisterTorchCallback(torchCb)
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
            SensorManager.GRAVITY_EARTH
        if (g < 2.5f) return
        val now = System.currentTimeMillis()
        if (now - lastToggle < 1500) return
        if (now - lastShake < 250) return
        shakeCount = if (now - lastShake < 900) shakeCount + 1 else 1
        lastShake = now
        if (shakeCount >= 2) {
            shakeCount = 0
            lastToggle = now
            toggle()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun toggle() {
        val id = camId ?: return
        try {
            cm.setTorchMode(id, !torchOn)
            torchOn = !torchOn
        } catch (_: CameraAccessException) {
        }
    }
}
