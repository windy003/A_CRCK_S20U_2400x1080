package com.example.bluetoothkeymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KeyMapperAccessibilityService : AccessibilityService() {
    
    private var audioManager: AudioManager? = null
    private var isTiktokModeEnabled = false // TikTok模式开关，默认关闭
    private var isYoutubeModeEnabled = false // YouTube模式开关，默认关闭
    private var lastMediaPlayPauseTime = 0L // 上次播放/暂停按键时间戳
    private var screenWidth = 0 // 屏幕宽度
    private var screenHeight = 0 // 屏幕高度
    private var currentForegroundApp = "" // 当前前台应用包名
    private var isAutoModeEnabled = true // 自动模式切换开关

    // 左方向键长按检测相关变量
    private var dpadLeftPressTime = 0L // 左方向键按下时间戳
    private var dpadLeftHandler: android.os.Handler? = null // 左方向键长按处理器
    private var isDpadLeftLongPressTriggered = false // 左方向键长按是否已触发

    // 右方向键长按检测相关变量
    private var dpadRightPressTime = 0L // 右方向键按下时间戳
    private var dpadRightHandler: android.os.Handler? = null // 右方向键长按处理器
    private var isDpadRightLongPressTriggered = false // 右方向键长按是否已触发

    // 下方向键长按检测相关变量
    private var dpadDownPressTime = 0L // 下方向键按下时间戳
    private var dpadDownHandler: android.os.Handler? = null // 下方向键长按处理器
    private var isDpadDownLongPressTriggered = false // 下方向键长按是否已触发

    // 语音键长按检测相关变量
    private var voicePressTime = 0L
    private var voiceHandler: android.os.Handler? = null
    private var isVoiceLongPressTriggered = false

    // 模式重置延迟相关（防止旋转屏幕时误重置）
    private var modeResetHandler: android.os.Handler? = null
    private var pendingModeReset: Runnable? = null
    private var lastYoutubeSeenTime = 0L  // 最后一次检测到YouTube的时间戳
    private var lastTiktokSeenTime = 0L   // 最后一次检测到TikTok的时间戳
    private val MODE_RESET_GUARD_MS = 5000L // 5秒内见过特殊应用则不重置

    companion object {
        private const val TAG = "KeyMapperAccessibility"
        var instance: KeyMapperAccessibilityService? = null
        private const val PREFS_NAME = "KeyMapperPrefs"
        private const val PREF_TIKTOK_MODE_ENABLED = "tiktok_mode_enabled"

        // 应用包名映射
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_LITE_PACKAGE = "com.zhiliaoapp.musically.go"
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private const val DOUYIN_LITE_PACKAGE = "com.ss.android.ugc.aweme.lite"
        private const val TOUTIAO_PACKAGE = "com.ss.android.article.news"

        // 系统应用和桌面应用，不应该触发模式切换
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.lge.launcher3",
            "com.android.launcher",
            "com.huawei.android.launcher",
            "com.xiaomi.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.samsung.android.launcher",
            "com.lge.displayfingerprint",
            "com.example.bluetoothkeymapper", // 自己的应用
            "android",
            "com.android.settings"
        )
    }
    
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        instance = this
        dpadLeftHandler = android.os.Handler()
        dpadRightHandler = android.os.Handler()
        dpadDownHandler = android.os.Handler()
        voiceHandler = android.os.Handler()
        modeResetHandler = android.os.Handler()
        
        // 从SharedPreferences读取初始状态
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isTiktokModeEnabled = sharedPreferences.getBoolean(PREF_TIKTOK_MODE_ENABLED, false)
        isAutoModeEnabled = sharedPreferences.getBoolean("auto_mode_enabled", true)

        Log.d(TAG, "无障碍服务已创建")
        Log.d(TAG, "TikTok模式初始状态: ${if (isTiktokModeEnabled) "开启" else "关闭"}")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.e(TAG, "!!! 无障碍服务已连接 !!!")
        Log.e(TAG, "服务信息: ${serviceInfo}")
        Log.e(TAG, "可处理事件类型: ${serviceInfo?.eventTypes}")
        Log.e(TAG, "可过滤按键事件: ${serviceInfo?.flags?.and(android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0}")

        // 获取屏幕尺寸并自动检测比例
        getScreenDimensions()

        // 测试日志输出
        android.os.Handler().postDelayed({
            Log.e(TAG, "无障碍服务准备就绪，开始监听所有按键事件")
            Log.e(TAG, "TikTok模式状态: ${if (isTiktokModeEnabled) "开启" else "关闭"}")
            Log.e(TAG, "自动模式状态: ${if (isAutoModeEnabled) "开启" else "关闭"}")
            Log.i(TAG, "dpad left: TikTok模式左滑 / 默认上一曲")
            Log.i(TAG, "dpad right: TikTok模式右滑 / 默认下一曲")
            Log.i(TAG, "dpad down: 长按截屏")
            Log.i(TAG, "move home key (122): TikTok模式重置进度 / 默认上一曲")
            Log.i(TAG, "menu key: 下一曲")
            Log.i(TAG, "请按下蓝牙遥控器按键进行测试")
            android.util.Log.wtf(TAG, "最高级别日志：等待按键事件...")
        }, 1000)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "配置发生变化，重新检测屏幕比例")

        // 延迟一下再获取屏幕尺寸，确保配置变化完成
        android.os.Handler().postDelayed({
            getScreenDimensions()
        }, 100)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 监听窗口状态变化事件，检测前台应用切换
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                if (!packageName.isNullOrEmpty() && packageName != currentForegroundApp) {
                    // 忽略系统应用的窗口变化，避免影响按键处理
                    if (SYSTEM_PACKAGES.contains(packageName)) {
                        Log.d(TAG, "检测到系统应用窗口: $packageName，不更新前台应用")
                        return
                    }

                    currentForegroundApp = packageName
                    Log.e(TAG, "=== 前台应用切换 ===")
                    Log.e(TAG, "新应用: $packageName")
                    Log.e(TAG, "自动模式: ${if (isAutoModeEnabled) "开启" else "关闭"}")

                    if (isAutoModeEnabled) {
                        Log.e(TAG, "执行自动模式切换...")
                        // 根据应用包名自动切换模式
                        checkAndSwitchModeByApp(packageName)
                    } else {
                        Log.e(TAG, "自动模式已关闭，跳过模式切换")
                    }
                    Log.e(TAG, "==================")
                }
            }
        }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    private fun getScreenDimensions() {
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        val aspectRatio = if (screenWidth > screenHeight) screenWidth.toFloat() / screenHeight else screenHeight.toFloat() / screenWidth
        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}, 比例: $aspectRatio")
    }

    private fun checkAndSwitchModeByApp(packageName: String) {
        if (!isAutoModeEnabled) return

        Log.e(TAG, "=== 应用模式检查 ===")
        Log.e(TAG, "包名: $packageName, TikTok模式: $isTiktokModeEnabled")

        when (packageName) {
            TIKTOK_PACKAGE, TIKTOK_LITE_PACKAGE, DOUYIN_PACKAGE, DOUYIN_LITE_PACKAGE, TOUTIAO_PACKAGE -> {
                lastTiktokSeenTime = System.currentTimeMillis()
                pendingModeReset?.let { modeResetHandler?.removeCallbacks(it) }
                pendingModeReset = null
                Log.e(TAG, "匹配到TikTok/抖音/今日头条应用，切换到TikTok模式")
                if (!isTiktokModeEnabled) switchToMode("tiktok")
                else Log.e(TAG, "已经是TikTok模式，无需切换")
            }
            YOUTUBE_PACKAGE, YOUTUBE_MUSIC_PACKAGE -> {
                lastYoutubeSeenTime = System.currentTimeMillis()
                pendingModeReset?.let { modeResetHandler?.removeCallbacks(it) }
                pendingModeReset = null
                Log.e(TAG, "匹配到YouTube应用，切换到YouTube模式")
                if (!isYoutubeModeEnabled) switchToMode("youtube")
                else Log.e(TAG, "已经是YouTube模式，无需切换")
            }
            else -> {
                if (SYSTEM_PACKAGES.contains(packageName)) {
                    Log.e(TAG, "系统应用: $packageName，保持当前模式")
                    return
                }
                if (isTiktokModeEnabled || isYoutubeModeEnabled) {
                    Log.e(TAG, "检测到非特殊应用($packageName)，延迟2秒后确认是否恢复默认模式")
                    pendingModeReset?.let { modeResetHandler?.removeCallbacks(it) }
                    val resetRunnable = Runnable {
                        val now = System.currentTimeMillis()
                        val recentYoutube = isYoutubeModeEnabled && (now - lastYoutubeSeenTime < MODE_RESET_GUARD_MS)
                        val recentTiktok = isTiktokModeEnabled && (now - lastTiktokSeenTime < MODE_RESET_GUARD_MS)
                        if (recentYoutube || recentTiktok) {
                            Log.e(TAG, "5秒内还见过特殊应用，取消模式重置（旋转保护）")
                        } else if (isTiktokModeEnabled || isYoutubeModeEnabled) {
                            Log.e(TAG, "延迟确认：恢复默认模式")
                            switchToMode("default")
                        }
                        pendingModeReset = null
                    }
                    pendingModeReset = resetRunnable
                    modeResetHandler?.postDelayed(resetRunnable, 2000)
                }
            }
        }
        Log.e(TAG, "=================")
    }

    private fun switchToMode(mode: String) {
        Log.e(TAG, "=== 切换模式: $mode ===")
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (mode) {
            "tiktok" -> {
                isTiktokModeEnabled = true
                isYoutubeModeEnabled = false
                sharedPreferences.edit().putBoolean(PREF_TIKTOK_MODE_ENABLED, true).commit()
                Log.e(TAG, "✅ 已切换到TikTok模式")
            }
            "youtube" -> {
                isYoutubeModeEnabled = true
                isTiktokModeEnabled = false
                sharedPreferences.edit().putBoolean(PREF_TIKTOK_MODE_ENABLED, false).commit()
                Log.e(TAG, "✅ 已切换到YouTube模式")
            }
            "default" -> {
                isTiktokModeEnabled = false
                isYoutubeModeEnabled = false
                sharedPreferences.edit().putBoolean(PREF_TIKTOK_MODE_ENABLED, false).commit()
                Log.e(TAG, "✅ 已恢复默认模式")
            }
        }
        Log.e(TAG, "=== 模式切换完成 ===")
    }

    // 判断当前是否为16:9小屏（投屏模式），还是20:9大屏
    private fun is16to9Screen(): Boolean {
        val aspectRatio = maxOf(screenWidth, screenHeight).toFloat() / minOf(screenWidth, screenHeight)
        return aspectRatio < 2.0f // 16:9≈1.78，20:9≈2.22
    }

    private fun getSwipePixels(): Int {
        val sharedPreferences = getSharedPreferences("TikTokRemoteControl", MODE_PRIVATE)
        return sharedPreferences.getInt("swipe_pixels", 100)
    }

    private fun isTiktokServiceEnabled(): Boolean {
        val sharedPreferences = getSharedPreferences("TikTokRemoteControl", MODE_PRIVATE)
        return sharedPreferences.getBoolean("service_master_switch", true)
    }
    
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 强制记录所有按键事件，确保日志可见
        android.util.Log.i(TAG, "!!! 捕获按键事件 !!!")
        android.util.Log.i(TAG, "键码: ${event.keyCode}")
        android.util.Log.i(TAG, "动作: ${event.action} (${if (event.action == KeyEvent.ACTION_DOWN) "按下" else if (event.action == KeyEvent.ACTION_UP) "松开" else "其他"})")
        android.util.Log.i(TAG, "设备: ${event.device?.name ?: "未知"}")
        android.util.Log.i(TAG, "外部设备: ${event.device?.isExternal ?: false}")
        android.util.Log.i(TAG, "扫描码: ${event.scanCode}")

        // 详细的调试日志
        val deviceInfo = event.device?.let { device ->
            "name='${device.name}', id=${device.id}, productId=${device.productId}, vendorId=${device.vendorId}, isExternal=${device.isExternal}"
        } ?: "null"

        Log.w(TAG, "=== 完整按键事件详情 ===")
        Log.w(TAG, "键码: ${event.keyCode}")
        Log.w(TAG, "动作: ${if (event.action == KeyEvent.ACTION_DOWN) "按下" else if (event.action == KeyEvent.ACTION_UP) "松开" else "其他(${event.action})"}")
        Log.w(TAG, "设备: $deviceInfo")
        Log.w(TAG, "扫描码: ${event.scanCode}")
        Log.w(TAG, "重复次数: ${event.repeatCount}")
        Log.w(TAG, "==========================")

        // 系统应用不拦截按键，其余所有应用统一处理
        if (SYSTEM_PACKAGES.contains(currentForegroundApp)) {
            Log.w(TAG, "当前为系统应用 ($currentForegroundApp)，不处理按键事件")
            return super.onKeyEvent(event)
        }

        Log.i(TAG, "当前应用: $currentForegroundApp，TikTok模式: $isTiktokModeEnabled，继续处理按键事件")
        
        // 处理Enter键和其他OK键 - 根据模式进行不同映射
        when (event.keyCode) {
            60,                              // 遥控器Enter键
            KeyEvent.KEYCODE_ENTER,          // 66 标准Enter键
            KeyEvent.KEYCODE_DPAD_CENTER,    // 23 方向键中心
            KeyEvent.KEYCODE_NUMPAD_ENTER,   // 160 数字键盘Enter
            KeyEvent.KEYCODE_BUTTON_A,       // 96 游戏手柄A键
            KeyEvent.KEYCODE_BUTTON_SELECT,  // 109 选择键
            KeyEvent.KEYCODE_BUTTON_START,   // 108 开始键
            13, 28, 158, 352,               // 其他可能的Enter键码
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, // 85 媒体播放暂停键
            KeyEvent.KEYCODE_MEDIA_PLAY,     // 126 媒体播放键
            KeyEvent.KEYCODE_SPACE -> {      // 62 空格键
                Log.e(TAG, "!!! 检测到目标按键: ${event.keyCode} !!!")

                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (isTiktokModeEnabled) {
                        Log.e(TAG, "TikTok模式 - 执行屏幕中心点击操作")
                        performTiktokCenterClick()
                    } else {
                        Log.e(TAG, "执行媒体播放暂停键")
                        sendMediaPlayPause()
                    }
                }
                return true // 拦截原始事件
            }
            
            // 处理dpad left键 - 支持长按检测
            KeyEvent.KEYCODE_DPAD_LEFT -> {  // 21 方向键左
                Log.e(TAG, "!!! 检测到dpad left按键: ${event.keyCode} !!!")

                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        // 记录按下时间
                        dpadLeftPressTime = System.currentTimeMillis()
                        isDpadLeftLongPressTriggered = false

                        // 设置1秒后触发长按事件
                        dpadLeftHandler?.postDelayed({
                            if (dpadLeftPressTime > 0 && !isDpadLeftLongPressTriggered) {
                                isDpadLeftLongPressTriggered = true
                                handleDpadLeftLongPress()
                            }
                        }, 1000) // 1秒长按

                        Log.e(TAG, "左方向键按下，开始计时...")
                    }

                    KeyEvent.ACTION_UP -> {
                        val pressDuration = System.currentTimeMillis() - dpadLeftPressTime
                        Log.e(TAG, "左方向键松开，按下时长: ${pressDuration}ms")

                        // 清除长按计时器
                        dpadLeftHandler?.removeCallbacksAndMessages(null)

                        if (!isDpadLeftLongPressTriggered && pressDuration < 1000) {
                            // 短按：执行原有的功能
                            handleDpadLeftShortPress()
                        }

                        // 重置状态
                        dpadLeftPressTime = 0L
                        isDpadLeftLongPressTriggered = false
                    }
                }
                return true // 拦截原始事件
            }
            
            // 处理dpad down键
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isYoutubeModeEnabled) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        when {
                            isLandscape && is16to9Screen() -> {
                                Log.e(TAG, "YouTube模式16:9横屏 - 下方向键单击(1629,315)")
                                performSingleClick(1629f, 315f)
                            }
                            isLandscape -> {
                                Log.e(TAG, "YouTube模式20:9横屏 - 下方向键单击(1855,434)")
                                performSingleClick(1855f, 434f)
                            }
                            is16to9Screen() -> {
                                Log.e(TAG, "YouTube模式16:9竖屏 - 下方向键单击(931,346)")
                                performSingleClick(931f, 346f)
                            }
                            else -> {
                                Log.e(TAG, "YouTube模式20:9竖屏 - 下方向键单击(948,347)")
                                performSingleClick(948f, 347f)
                            }
                        }
                    }
                } else {
                    // 非YouTube模式：长按截屏
                    if (event.action == KeyEvent.ACTION_DOWN && dpadDownPressTime == 0L) {
                        dpadDownPressTime = System.currentTimeMillis()
                        isDpadDownLongPressTriggered = false
                        dpadDownHandler?.postDelayed({
                            if (dpadDownPressTime > 0L && !isDpadDownLongPressTriggered) {
                                isDpadDownLongPressTriggered = true
                                performScreenshot()
                            }
                        }, 500)
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        dpadDownHandler?.removeCallbacksAndMessages(null)
                        dpadDownPressTime = 0L
                        isDpadDownLongPressTriggered = false
                    }
                }
                return true
            }

            // 处理返回按键
            KeyEvent.KEYCODE_BACK -> {
                if (isYoutubeModeEnabled && event.action == KeyEvent.ACTION_DOWN) {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    when {
                        isLandscape && is16to9Screen() -> {
                            Log.e(TAG, "YouTube模式16:9横屏 - 返回键单击(1629,315)")
                            performSingleClick(1629f, 315f)
                        }
                        isLandscape -> {
                            Log.e(TAG, "YouTube模式20:9横屏 - 返回键单击(1855,434)")
                            performSingleClick(1855f, 434f)
                        }
                        is16to9Screen() -> {
                            Log.e(TAG, "YouTube模式16:9竖屏 - 返回键单击(931,346)")
                            performSingleClick(931f, 346f)
                        }
                        else -> {
                            Log.e(TAG, "YouTube模式20:9竖屏 - 返回键单击(948,347)")
                            performSingleClick(948f, 347f)
                        }
                    }
                    return true
                }
                return super.onKeyEvent(event)
            }

            // 处理home按键
            122 -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when {
                        isTiktokModeEnabled -> {
                            Log.e(TAG, "TikTok模式 - Home键重置进度条")
                            performTiktokSeekToStart()
                        }
                        isYoutubeModeEnabled -> {
                            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            when {
                                isLandscape && is16to9Screen() -> {
                                    Log.e(TAG, "YouTube模式16:9横屏 - Home键(1852,799)")
                                    performSingleClick(1852f, 799f)
                                }
                                isLandscape -> {
                                    Log.e(TAG, "YouTube模式20:9横屏 - Home键(2261,801)")
                                    performSingleClick(2261f, 801f)
                                }
                                is16to9Screen() -> {
                                    Log.e(TAG, "YouTube模式16:9竖屏 - Home键(1014,613)")
                                    performSingleClick(1014f, 613f)
                                }
                                else -> {
                                    Log.e(TAG, "YouTube模式20:9竖屏 - Home键(1012,625)")
                                    performSingleClick(1012f, 625f)
                                }
                            }
                        }
                        else -> {
                            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (isLandscape) {
                                Log.e(TAG, "默认模式横屏 - Home键单击(2261,801)")
                                performSingleClick(2261f, 801f)
                            } else {
                                sendMediaPrevious()
                            }
                        }
                    }
                }
                return true
            }

            // 处理menu按键
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when {
                        isYoutubeModeEnabled -> {
                            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            when {
                                isLandscape && is16to9Screen() -> {
                                    Log.e(TAG, "YouTube模式16:9横屏 - Menu键(1760,82)")
                                    performSingleClick(1760f, 82f)
                                }
                                isLandscape -> {
                                    Log.e(TAG, "YouTube模式20:9横屏 - Menu键(2151,68)")
                                    performSingleClick(2151f, 68f)
                                }
                                is16to9Screen() -> {
                                    Log.e(TAG, "YouTube模式16:9竖屏 - Menu键(900,144)")
                                    performSingleClick(900f, 144f)
                                }
                                else -> {
                                    Log.e(TAG, "YouTube模式20:9竖屏 - Menu键(855,182)")
                                    performSingleClick(855f, 182f)
                                }
                            }
                        }
                        else -> {
                            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (isLandscape) {
                                Log.e(TAG, "默认模式横屏 - Menu键单击(2151,68)")
                                performSingleClick(2151f, 68f)
                            } else {
                                sendMediaNext()
                            }
                        }
                    }
                }
                return true
            }

            // 处理dpad right键 - 支持长按检测
            KeyEvent.KEYCODE_DPAD_RIGHT -> {  // 22 方向键右
                Log.e(TAG, "!!! 检测到dpad right按键: ${event.keyCode} !!!")

                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        // 记录按下时间
                        dpadRightPressTime = System.currentTimeMillis()
                        isDpadRightLongPressTriggered = false

                        // 设置1秒后触发长按事件
                        dpadRightHandler?.postDelayed({
                            if (dpadRightPressTime > 0 && !isDpadRightLongPressTriggered) {
                                isDpadRightLongPressTriggered = true
                                handleDpadRightLongPress()
                            }
                        }, 1000) // 1秒长按

                        Log.e(TAG, "右方向键按下，开始计时...")
                    }

                    KeyEvent.ACTION_UP -> {
                        val pressDuration = System.currentTimeMillis() - dpadRightPressTime
                        Log.e(TAG, "右方向键松开，按下时长: ${pressDuration}ms")

                        // 清除长按计时器
                        dpadRightHandler?.removeCallbacksAndMessages(null)

                        if (!isDpadRightLongPressTriggered && pressDuration < 1000) {
                            // 短按：执行原有的功能
                            handleDpadRightShortPress()
                        }

                        // 重置状态
                        dpadRightPressTime = 0L
                        isDpadRightLongPressTriggered = false
                    }
                }
                return true // 拦截原始事件
            }

            // 处理语音键(135) - 支持长按检测
            135 -> {
                Log.e(TAG, "!!! 检测到语音键: ${event.keyCode} !!!")

                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        voicePressTime = System.currentTimeMillis()
                        isVoiceLongPressTriggered = false

                        voiceHandler?.postDelayed({
                            if (voicePressTime > 0 && !isVoiceLongPressTriggered) {
                                isVoiceLongPressTriggered = true
                                handleVoiceLongPress()
                            }
                        }, 1000)

                        Log.e(TAG, "语音键按下，开始计时...")
                    }

                    KeyEvent.ACTION_UP -> {
                        val pressDuration = System.currentTimeMillis() - voicePressTime
                        Log.e(TAG, "语音键松开，按下时长: ${pressDuration}ms")

                        voiceHandler?.removeCallbacksAndMessages(null)

                        if (!isVoiceLongPressTriggered && pressDuration < 1000) {
                            handleVoiceShortPress()
                        }

                        voicePressTime = 0L
                        isVoiceLongPressTriggered = false
                    }
                }
                return true
            }

        }

        // 记录所有未处理的按键
        Log.d(TAG, "未处理的按键: ${event.keyCode}")
        return super.onKeyEvent(event)
    }
    
    private fun sendMediaPlayPause() {
        try {
            val currentTime = System.currentTimeMillis()
            val timeDifference = currentTime - lastMediaPlayPauseTime
            
            // 如果距离上次点击不足2秒，则忽略此次点击
            if (timeDifference < 500) {
                Log.w(TAG, "播放/暂停按键被屏蔽 - 距离上次点击仅${timeDifference}ms (需要2000ms)")
                return
            }
            
            Log.e(TAG, "发送媒体播放暂停键...")
            
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            
            // 通过AudioManager发送媒体按键
            val result1 = audioManager?.dispatchMediaKeyEvent(downEvent)
            Thread.sleep(50)
            val result2 = audioManager?.dispatchMediaKeyEvent(upEvent)
            
            // 更新最后一次播放/暂停按键的时间
            lastMediaPlayPauseTime = currentTime
            
            Log.e(TAG, "媒体按键发送结果: down=$result1, up=$result2")
        } catch (e: Exception) {
            Log.e(TAG, "发送媒体按键失败: ${e.message}")
        }
    }
    
    private fun sendMediaPrevious() {
        try {
            Log.e(TAG, "发送媒体上一曲按键...")
            
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            
            // 通过AudioManager发送媒体按键
            val result1 = audioManager?.dispatchMediaKeyEvent(downEvent)
            Thread.sleep(50)
            val result2 = audioManager?.dispatchMediaKeyEvent(upEvent)
            
            Log.e(TAG, "媒体上一曲按键发送结果: down=$result1, up=$result2")
        } catch (e: Exception) {
            Log.e(TAG, "发送媒体上一曲按键失败: ${e.message}")
        }
    }
    
    private fun sendMediaNext() {
        try {
            Log.e(TAG, "发送媒体下一曲按键...")

            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)

            // 通过AudioManager发送媒体按键
            val result1 = audioManager?.dispatchMediaKeyEvent(downEvent)
            Thread.sleep(50)
            val result2 = audioManager?.dispatchMediaKeyEvent(upEvent)

            Log.e(TAG, "媒体下一曲按键发送结果: down=$result1, up=$result2")
        } catch (e: Exception) {
            Log.e(TAG, "发送媒体下一曲按键失败: ${e.message}")
        }
    }

    fun setAutoModeEnabled(enabled: Boolean) {
        isAutoModeEnabled = enabled
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit().putBoolean("auto_mode_enabled", enabled).apply()
        Log.d(TAG, "自动模式切换已${if (enabled) "开启" else "关闭"}")
    }

    private fun performTiktokLeftSwipe() {
        if (!isTiktokServiceEnabled()) {
            Log.d(TAG, "TikTok服务已关闭，忽略左滑手势")
            return
        }

        val swipePixels = getSwipePixels()
        val path = Path().apply {
            val startX = 566f
            val endX = startX - swipePixels.toFloat()  // 向左滑动客制化像素
            val y = 2270f
            moveTo(startX, y)
            lineTo(endX, y)
        }
        performTiktokSwipeGesture(path)
        Log.d(TAG, "执行TikTok左滑手势: 从(566,2270)向左滑动${swipePixels}px")
    }

    private fun performTiktokRightSwipe() {
        if (!isTiktokServiceEnabled()) {
            Log.d(TAG, "TikTok服务已关闭，忽略右滑手势")
            return
        }

        val swipePixels = getSwipePixels()
        val path = Path().apply {
            val startX = 566f
            val endX = startX + swipePixels.toFloat()  // 向右滑动客制化像素
            val y = 2270f
            moveTo(startX, y)
            lineTo(endX, y)
        }
        performTiktokSwipeGesture(path)
        Log.d(TAG, "执行TikTok右滑手势: 从(566,2270)向右滑动${swipePixels}px")
    }

    private fun performTiktokCenterClick() {
        if (!isTiktokServiceEnabled()) {
            Log.d(TAG, "TikTok服务已关闭，忽略中心点击手势")
            return
        }

        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f

        val path = Path().apply {
            moveTo(centerX, centerY)
            lineTo(centerX, centerY)  // 点击手势，起始和结束位置相同
        }
        performTiktokClickGesture(path)
        Log.d(TAG, "执行TikTok屏幕中心点击: 位置(${centerX},${centerY})")
    }

    private fun performTiktokSeekToStart() {
        if (!isTiktokServiceEnabled()) {
            Log.d(TAG, "TikTok服务已关闭，忽略进度条重置手势")
            return
        }

        val path = Path().apply {
            val startX = 900f   // 从适中位置开始，避免被误认为返回手势
            val endX = 0f       // 滑动到最左边(0位置)，实现跳转到开头
            val y = 2270f       // 使用与左右滑动相同的Y坐标
            moveTo(startX, y)
            lineTo(endX, y)
        }
        performTiktokSwipeGesture(path)
        Log.d(TAG, "执行TikTok进度条重置: 从(900,2270)滑动到(0,2270)")
    }

    private fun performTiktokSwipeGesture(path: Path) {
        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 300L)
        gestureBuilder.addStroke(strokeDescription)

        val gesture = gestureBuilder.build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "TikTok手势执行完成")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "TikTok手势执行被取消")
            }
        }, null)
    }

    private fun performTiktokClickGesture(path: Path) {
        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 100L)
        gestureBuilder.addStroke(strokeDescription)

        val gesture = gestureBuilder.build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "TikTok点击手势执行完成")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "TikTok点击手势执行被取消")
            }
        }, null)
    }

    // 处理左方向键短按
    private fun handleDpadLeftShortPress() {
        when {
            isTiktokModeEnabled -> {
                Log.e(TAG, "TikTok模式 - 左滑操作")
                performTiktokLeftSwipe()
            }
            isYoutubeModeEnabled -> {
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                when {
                    isLandscape && is16to9Screen() -> {
                        Log.e(TAG, "YouTube模式16:9横屏 - 左方向键双击(352,437)")
                        performDoubleClick(352f, 437f)
                    }
                    isLandscape -> {
                        Log.e(TAG, "YouTube模式20:9横屏 - 左方向键双击(321,330)")
                        performDoubleClick(321f, 330f)
                    }
                    is16to9Screen() -> {
                        Log.e(TAG, "YouTube模式16:9竖屏 - 左方向键双击(143,225)")
                        performDoubleClick(143f, 225f)
                    }
                    else -> {
                        Log.e(TAG, "YouTube模式20:9竖屏 - 左方向键双击(150,221)")
                        performDoubleClick(150f, 221f)
                    }
                }
            }
            else -> {
                Log.e(TAG, "默认模式 - 上一曲")
                sendMediaPrevious()
            }
        }
    }

    // 处理左方向键长按1秒
    private fun handleDpadLeftLongPress() {
        Log.e(TAG, "左方向键长按 - 上一曲")
        sendMediaPrevious()
    }

    // 处理右方向键短按
    private fun handleDpadRightShortPress() {
        when {
            isTiktokModeEnabled -> {
                Log.e(TAG, "TikTok模式 - 右滑操作")
                performTiktokRightSwipe()
            }
            isYoutubeModeEnabled -> {
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                when {
                    isLandscape && is16to9Screen() -> {
                        Log.e(TAG, "YouTube模式16:9横屏 - 右方向键双击(1629,315)")
                        performDoubleClick(1629f, 315f)
                    }
                    isLandscape -> {
                        Log.e(TAG, "YouTube模式20:9横屏 - 右方向键双击(1855,434)")
                        performDoubleClick(1855f, 434f)
                    }
                    is16to9Screen() -> {
                        Log.e(TAG, "YouTube模式16:9竖屏 - 右方向键双击(931,346)")
                        performDoubleClick(931f, 346f)
                    }
                    else -> {
                        Log.e(TAG, "YouTube模式20:9竖屏 - 右方向键双击(869,287)")
                        performDoubleClick(869f, 287f)
                    }
                }
            }
            else -> {
                Log.e(TAG, "默认模式 - 下一曲")
                sendMediaNext()
            }
        }
    }

    // 处理右方向键长按1秒（播放下一个）
    private fun handleDpadRightLongPress() {
        Log.e(TAG, "右方向键长按1秒触发 - 播放下一个")
        sendMediaNext()
        Log.e(TAG, "右方向键长按 - 下一个播放操作完成")
    }

    // 处理语音键单击
    private fun handleVoiceShortPress() {
        if (isYoutubeModeEnabled) {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape && is16to9Screen()) {
                Log.e(TAG, "YouTube模式16:9横屏 - 语音键单击(532,87)出现说明面板")
                performSingleClick(532f, 87f)
            }
        }
    }

    // 处理语音键长按
    private fun handleVoiceLongPress() {
        if (isYoutubeModeEnabled) {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape && is16to9Screen()) {
                Log.e(TAG, "YouTube模式16:9横屏 - 语音键长按(1881,80)关闭说明面板")
                performSingleClick(1881f, 80f)
            }
        }
    }

    // 执行单击操作
    private fun performSingleClick(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "单击操作完成: ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "单击操作失败: ${e.message}")
        }
    }

    // 执行双击操作
    private fun performDoubleClick(x: Float, y: Float) {
        try {
            val firstPath = Path().apply { moveTo(x, y) }
            val firstStroke = GestureDescription.StrokeDescription(firstPath, 0, 100)
            val firstGesture = GestureDescription.Builder().addStroke(firstStroke).build()

            dispatchGesture(firstGesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    // 第一次点击完成后，延迟100ms执行第二次点击
                    android.os.Handler().postDelayed({
                        val secondPath = Path().apply { moveTo(x, y) }
                        val secondStroke = GestureDescription.StrokeDescription(secondPath, 0, 100)
                        val secondGesture = GestureDescription.Builder().addStroke(secondStroke).build()
                        dispatchGesture(secondGesture, null, null)
                        Log.d(TAG, "双击第二次点击完成: ($x, $y)")
                    }, 100)
                }
            }, null)
            Log.d(TAG, "双击操作已发起: ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "双击操作失败: ${e.message}")
        }
    }

    // 执行截屏操作
    private fun performScreenshot() {
        try {
            Log.d(TAG, "执行截屏操作")
            // 使用无障碍服务的全局操作来执行截屏
            val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (result) {
                Log.d(TAG, "截屏操作成功触发")
            } else {
                Log.e(TAG, "截屏操作失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行截屏时出错: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        dpadLeftHandler?.removeCallbacksAndMessages(null)
        dpadLeftHandler = null
        dpadRightHandler?.removeCallbacksAndMessages(null)
        dpadRightHandler = null
        dpadDownHandler?.removeCallbacksAndMessages(null)
        dpadDownHandler = null
        voiceHandler?.removeCallbacksAndMessages(null)
        voiceHandler = null
        pendingModeReset?.let { modeResetHandler?.removeCallbacks(it) }
        modeResetHandler = null
        pendingModeReset = null
        Log.d(TAG, "无障碍服务已销毁")
    }
}