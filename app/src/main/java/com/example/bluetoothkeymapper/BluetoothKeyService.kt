package com.example.bluetoothkeymapper

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat

class BluetoothKeyService : Service() {
    
    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isServiceStarted = false
    
    companion object {
        private const val TAG = "BluetoothKeyService"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): BluetoothKeyService = this@BluetoothKeyService
    }
    
    override fun onCreate() {
        super.onCreate()
        initializeService()
    }
    
    private fun initializeService() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceStarted = true
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