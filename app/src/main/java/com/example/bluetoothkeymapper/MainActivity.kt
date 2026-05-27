package com.example.bluetoothkeymapper

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.app.ActivityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bluetoothkeymapper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var bluetoothKeyService: BluetoothKeyService? = null
    private var serviceBound = false
    private lateinit var sharedPreferences: SharedPreferences
    private var isReceiverRegistered = false
    private var isServiceRunning = false
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "KeyMapperPrefs"
        private const val PREF_SERVICE_ENABLED = "service_enabled"
        private const val PREF_AUTO_MODE_ENABLED = "auto_mode_enabled"
    }
    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
    
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            updateUI()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能使用此应用", Toast.LENGTH_LONG).show()
        }
    }
    
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        updateUI()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "未授予通知权限，前台常驻通知将不显示（服务仍会运行）", Toast.LENGTH_LONG).show()
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as BluetoothKeyService.LocalBinder
            bluetoothKeyService = binder.getService()
            serviceBound = true
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName) {
            bluetoothKeyService = null
            serviceBound = false
            updateUI()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        setupUI()
        checkPermissions()
        checkNotificationPermission()
        checkServiceRunningState()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    private fun setupUI() {
        Log.d(TAG, "设置UI")
        
        binding.btnToggleService.setOnClickListener {
            Log.d(TAG, "点击服务切换按钮, 当前状态: $isServiceRunning")
            if (isServiceRunning) {
                stopBluetoothService()
            } else {
                startBluetoothService()
            }
        }
        
        binding.btnAccessibilitySettings.setOnClickListener {
            Log.d(TAG, "点击无障碍设置按钮")
            openAccessibilitySettings()
        }

        binding.btnBatteryOptimization.setOnClickListener {
            Log.d(TAG, "点击关闭电池优化按钮")
            requestIgnoreBatteryOptimization()
        }
        
        binding.btnPermissions.setOnClickListener {
            Log.d(TAG, "点击权限按钮")
            requestPermissions.launch(bluetoothPermissions)
        }
        
        binding.btnEnableBluetooth.setOnClickListener {
            Log.d(TAG, "点击启用蓝牙按钮")
            enableBluetooth()
        }

        // 设置视频时长保存按钮点击事件
        binding.btnSavePixels.setOnClickListener {
            Log.d(TAG, "点击保存视频时长按钮")
            saveVideoDuration()
        }
        
        // 设置自动模式开关
        val isAutoModeEnabled = sharedPreferences.getBoolean(PREF_AUTO_MODE_ENABLED, true)
        binding.switchAutoMode.isChecked = isAutoModeEnabled
        updateAutoModeStatus(isAutoModeEnabled)

        // 初始化视频时长设置
        loadVideoDuration()

        // 设置开关监听器
        setAllModeListeners()
        
    }

    private fun setAutoModeSwitchListener() {
        binding.switchAutoMode.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "自动模式开关状态改变: $isChecked")

            // 保存状态
            sharedPreferences.edit()
                .putBoolean(PREF_AUTO_MODE_ENABLED, isChecked)
                .apply()

            // 更新无障碍服务状态
            KeyMapperAccessibilityService.instance?.setAutoModeEnabled(isChecked)

            // 更新UI显示
            updateAutoModeStatus(isChecked)

            Toast.makeText(
                this,
                if (isChecked) "自动模式已开启 - 将根据当前应用自动切换模式" else "自动模式已关闭 - 需要手动切换模式",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateAutoModeStatus(enabled: Boolean) {
        binding.tvAutoModeStatus.text = if (enabled) {
            "自动模式已开启 - 根据当前应用自动切换模式"
        } else {
            "自动模式已关闭 - 需要手动切换模式"
        }

        binding.tvAutoModeStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    private fun checkServiceRunningState() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var serviceActuallyRunning = false

            for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
                if (BluetoothKeyService::class.java.name == service.service.className) {
                    serviceActuallyRunning = true
                    break
                }
            }

            // 检查用户上次是否主动启动了服务
            val wasServiceEnabledByUser = sharedPreferences.getBoolean(PREF_SERVICE_ENABLED, false)

            if (serviceActuallyRunning && !wasServiceEnabledByUser) {
                // 服务在运行但用户上次关闭了它，停止服务
                Log.d(TAG, "检测到服务在运行但用户上次已关闭，自动停止服务")
                val intent = Intent(this, BluetoothKeyService::class.java)
                stopService(intent)
                isServiceRunning = false
            } else if (serviceActuallyRunning && wasServiceEnabledByUser) {
                // 服务在运行且用户上次启动了它
                Log.d(TAG, "检测到服务正在运行且用户上次已启动")
                isServiceRunning = true
            } else if (!serviceActuallyRunning && wasServiceEnabledByUser) {
                // 服务没运行但用户上次启动了它，可能需要重启（但这里不自动重启，让用户手动点击）
                Log.d(TAG, "用户上次启动了服务但服务未运行，等待用户手动启动")
                isServiceRunning = false
            } else {
                // 服务没运行且用户上次也没启动
                Log.d(TAG, "服务未运行，用户上次未启动")
                isServiceRunning = false
            }

            updateServiceButton()
        } catch (e: Exception) {
            Log.e(TAG, "检查服务运行状态失败: ${e.message}")
            isServiceRunning = false
            updateServiceButton()
        }
    }
    
    private fun checkPermissions() {
        val missingPermissions = bluetoothPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissions.launch(bluetoothPermissions)
        } else {
            bindToService()
        }
    }
    
    private fun bindToService() {
        val intent = Intent(this, BluetoothKeyService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startBluetoothService() {
        Log.d(TAG, "startBluetoothService 被调用")
        
        val bluetoothEnabled = isBluetoothEnabled()
        val permissionsGranted = hasBluetoothPermissions()
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        
        Log.d(TAG, "蓝牙启用: $bluetoothEnabled, 权限: $permissionsGranted, 无障碍: $accessibilityEnabled")
        
        if (!bluetoothEnabled) {
            Toast.makeText(this, "请先启用蓝牙", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!permissionsGranted) {
            Toast.makeText(this, "请先授予蓝牙权限", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!accessibilityEnabled) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        
        try {
            Log.d(TAG, "启动服务")
            val intent = Intent(this, BluetoothKeyService::class.java)
            // Android 8+ 必须用 startForegroundService，服务会在内部调用 startForeground 进入前台
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bluetoothKeyService?.startBluetoothScanning()

            // 启动服务时顺便提醒用户关闭电池优化，避免后台被系统杀掉
            promptDisableBatteryOptimizationIfNeeded()

            isServiceRunning = true

            // 保存服务启动状态
            sharedPreferences.edit()
                .putBoolean(PREF_SERVICE_ENABLED, true)
                .apply()
            Log.d(TAG, "已保存服务启动状态")


            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
            updateServiceButton()
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
            Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopBluetoothService() {
        val intent = Intent(this, BluetoothKeyService::class.java)
        stopService(intent)

        isServiceRunning = false

        // 保存服务停止状态
        sharedPreferences.edit()
            .putBoolean(PREF_SERVICE_ENABLED, false)
            .apply()
        Log.d(TAG, "已保存服务停止状态")


        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
        updateServiceButton()
        updateUI()
    }
    
    private fun updateServiceButton() {
        if (isServiceRunning) {
            binding.btnToggleService.text = "停止服务"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_light)
            )
        } else {
            binding.btnToggleService.text = "启动服务"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
        }
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
    
    private fun enableBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter?.isEnabled != true) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }
    
    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter?.isEnabled == true
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        return bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        return accessibilityServices?.contains(
            "${packageName}/${KeyMapperAccessibilityService::class.java.name}"
        ) == true
    }

    // 检查是否已忽略电池优化（即允许后台常驻）
    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    // 跳转系统对话框请求忽略电池优化
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "当前系统无需设置电池优化", Toast.LENGTH_SHORT).show()
            return
        }
        if (isBatteryOptimizationIgnored()) {
            Toast.makeText(this, "已关闭电池优化，应用可后台常驻", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @android.annotation.SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "请求忽略电池优化失败，改为打开电池优化设置列表: ${e.message}")
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开电池优化设置，请手动在系统设置中允许后台运行", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 启动服务时若未关闭电池优化则自动弹出系统对话框
    private fun promptDisableBatteryOptimizationIfNeeded() {
        if (!isBatteryOptimizationIgnored()) {
            Toast.makeText(this, "为保持后台常驻，请允许忽略电池优化", Toast.LENGTH_LONG).show()
            requestIgnoreBatteryOptimization()
        }
    }
    
    private fun updateUI() {
        val bluetoothEnabled = isBluetoothEnabled()
        val permissionsGranted = hasBluetoothPermissions()
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        
        Log.d(TAG, "updateUI - 蓝牙: $bluetoothEnabled, 权限: $permissionsGranted, 无障碍: $accessibilityEnabled")
        
        val batteryIgnored = isBatteryOptimizationIgnored()

        binding.tvBluetoothStatus.text = if (bluetoothEnabled) "蓝牙: 已启用" else "蓝牙: 未启用"
        binding.tvPermissionStatus.text = if (permissionsGranted) "权限: 已授予" else "权限: 未授予"
        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) "无障碍服务: 已启用" else "无障碍服务: 未启用"
        binding.tvBatteryStatus.text = if (batteryIgnored) "电池优化: 已关闭（可后台常驻）" else "电池优化: 未关闭（可能被系统杀后台）"
        binding.tvBatteryStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (batteryIgnored) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        binding.btnBatteryOptimization.isEnabled = !batteryIgnored
        
        val canStartService = bluetoothEnabled && permissionsGranted && accessibilityEnabled
        Log.d(TAG, "服务按钮可用状态: $canStartService")
        
        binding.btnToggleService.isEnabled = canStartService || isServiceRunning
        binding.btnEnableBluetooth.isEnabled = !bluetoothEnabled && permissionsGranted
        binding.btnPermissions.isEnabled = !permissionsGranted
        
        // 更新服务按钮的显示状态
        updateServiceButton()
    }
    
    private fun saveVideoDuration() {
        val minutesText = binding.etMinutes.text.toString()
        val secondsText = binding.etSeconds.text.toString()

        try {
            val minutes = if (minutesText.isNotEmpty()) minutesText.toInt() else 0
            val seconds = if (secondsText.isNotEmpty()) secondsText.toInt() else 0

            if (minutes == 0 && seconds == 0) {
                Toast.makeText(this, "请输入视频时长", Toast.LENGTH_SHORT).show()
                return
            }

            if (seconds >= 60) {
                Toast.makeText(this, "秒数不能超过59", Toast.LENGTH_SHORT).show()
                return
            }

            // 计算总秒数
            val totalSeconds = minutes * 60 + seconds

            // 计算分块数（总秒数除以5）
            val blockCount = totalSeconds / 5

            if (blockCount == 0) {
                Toast.makeText(this, "视频时长太短，至少需要5秒", Toast.LENGTH_SHORT).show()
                return
            }

            // 计算每次滑动的像素值（1080除以分块数）
            val swipePixels = 1080 / blockCount

            // 保存视频时长和计算出的像素值
            sharedPreferences.edit()
                .putInt("video_minutes", minutes)
                .putInt("video_seconds", seconds)
                .putInt("swipe_pixels", swipePixels)
                .apply()

            val tiktokPrefs = getSharedPreferences("TikTokRemoteControl", Context.MODE_PRIVATE)
            tiktokPrefs.edit().putInt("swipe_pixels", swipePixels).apply()

            // 更新说明文字显示计算结果
            binding.tvCalculatedPixels.text =
                "视频时长: ${minutes}分${seconds}秒 | 总秒数: ${totalSeconds}秒 | 分块数: ${blockCount} | 每次滑动: ${swipePixels}px"

            Toast.makeText(this, "已保存设置，每次滑动 ${swipePixels}px", Toast.LENGTH_LONG).show()
            Log.d(TAG, "视频时长已保存: ${minutes}分${seconds}秒，总秒数: ${totalSeconds}秒，分块数: ${blockCount}，滑动像素: ${swipePixels}px")
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadVideoDuration() {
        val savedMinutes = sharedPreferences.getInt("video_minutes", 0)
        val savedSeconds = sharedPreferences.getInt("video_seconds", 0)
        val savedPixels = sharedPreferences.getInt("swipe_pixels", 100)

        if (savedMinutes > 0 || savedSeconds > 0) {
            binding.etMinutes.setText(savedMinutes.toString())
            binding.etSeconds.setText(savedSeconds.toString())

            val totalSeconds = savedMinutes * 60 + savedSeconds
            val blockCount = totalSeconds / 5

            binding.tvCalculatedPixels.text =
                "视频时长: ${savedMinutes}分${savedSeconds}秒 | 总秒数: ${totalSeconds}秒 | 分块数: ${blockCount} | 每次滑动: ${savedPixels}px"
        } else {
            binding.tvCalculatedPixels.text = "说明：输入视频总时长后，系统会自动计算每次滑动的像素值"
        }

        Log.d(TAG, "加载视频时长: ${savedMinutes}分${savedSeconds}秒，滑动像素: ${savedPixels}px")
    }

    private fun removeAllModeListeners() {
        binding.switchAutoMode.setOnCheckedChangeListener(null)
    }

    private fun setAllModeListeners() {
        setAutoModeSwitchListener()
    }

    override fun onResume() {
        super.onResume()

        // 检查服务运行状态
        checkServiceRunningState()

        updateUI()

        // 延迟一下再同步状态，确保无障碍服务已经处理完应用切换
        android.os.Handler().postDelayed({
            syncAllModeStates()
        }, 200)
    }

    private fun syncAllModeStates() {
        Log.e(TAG, "🔄 开始同步所有模式状态")

        removeAllModeListeners()

        val currentAutoModeState = sharedPreferences.getBoolean(PREF_AUTO_MODE_ENABLED, true)
        binding.switchAutoMode.isChecked = currentAutoModeState
        updateAutoModeStatus(currentAutoModeState)

        Log.e(TAG, "📊 当前自动模式状态: $currentAutoModeState")

        setAllModeListeners()

        Log.e(TAG, "✅ 模式状态同步完成")
    }
    
    
    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
        // 取消注册广播接收器
        
    }
}