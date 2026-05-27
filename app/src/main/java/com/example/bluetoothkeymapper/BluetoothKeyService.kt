package com.example.bluetoothkeymapper

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat

class BluetoothKeyService : Service() {

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isServiceStarted = false

    companion object {
        private const val TAG = "BluetoothKeyService"
        private const val CHANNEL_ID = "bluetooth_key_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothKeyService = this@BluetoothKeyService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 尽早进入前台，避免 startForegroundService 后 5 秒内未 startForeground 导致崩溃
        startAsForeground()
        initializeService()
    }

    private fun initializeService() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "蓝牙按键映射服务",
                NotificationManager.IMPORTANCE_LOW // 低优先级，不发声不打扰
            ).apply {
                description = "保持按键映射服务后台常驻运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("蓝牙按键映射器运行中")
            .setContentText("正在后台监听遥控器按键")
            .setSmallIcon(R.drawable.a1024x1024)
            .setContentIntent(contentIntent)
            .setOngoing(true) // 常驻通知，用户无法滑动清除
            .build()
    }

    private fun startAsForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "前台服务已启动，进程优先级已提升")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceStarted = true
        // 确保处于前台状态（被系统重启时 onCreate 已调用，这里再次确保）
        startAsForeground()
        startBluetoothScanning()
        // START_STICKY: 进程被杀后系统会尝试重建服务
        return START_STICKY
    }

    fun startBluetoothScanning() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "蓝牙未启用")
            return
        }

        // 检查蓝牙权限
        val hasBluetoothPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasBluetoothPermission) {
            Log.e(TAG, "缺少蓝牙权限")
            return
        }

        Log.d(TAG, "检查蓝牙设备连接状态")
        val pairedDevices = bluetoothAdapter?.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            Log.d(TAG, "没有配对的蓝牙设备")
            return
        }

        pairedDevices.forEach { device ->
            Log.d(TAG, "已配对设备: ${device.name} - ${device.address}")
        }

        Log.d(TAG, "蓝牙服务启动成功，按键映射由无障碍服务处理")
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceStarted = false
        Log.d(TAG, "蓝牙服务已停止")
    }
}
