
# 蓝牙按键映射器

这是一个Android应用，可以将蓝牙遥控器的Enter键映射为媒体播放/暂停功能，类似于Button Mapper应用的功能。

## 功能特性

- 监听蓝牙遥控器按键事件
- 将Enter键和D-Pad中心键映射为媒体播放/暂停
- 前台服务确保持续运行
- 直观的用户界面显示状态信息

## 使用方法

1. **启用蓝牙**：确保设备蓝牙已启用并与遥控器配对
2. **授予权限**：点击"请求权限"按钮授予必要的蓝牙权限
3. **启用无障碍服务**：
   - 点击"打开无障碍设置"按钮
   - 找到"蓝牙按键映射器"并启用
4. **启动服务**：所有条件满足后，点击"启动服务"开始使用

## 权限说明

应用需要以下权限：

- **BLUETOOTH/BLUETOOTH_CONNECT**：连接蓝牙设备
- **BLUETOOTH_SCAN**：扫描蓝牙设备
- **ACCESS_FINE_LOCATION**：蓝牙扫描需要位置权限
- **FOREGROUND_SERVICE**：运行前台服务
- **BIND_ACCESSIBILITY_SERVICE**：无障碍服务权限

## 技术架构

- **BluetoothKeyService**：前台服务，处理蓝牙连接和按键监听
- **KeyMapperAccessibilityService**：无障碍服务，拦截系统按键事件
- **MainActivity**：主界面，管理权限和服务状态

## 支持的按键

目前支持以下按键映射为媒体播放/暂停：
- Enter键 (KEYCODE_ENTER)
- D-Pad中心键 (KEYCODE_DPAD_CENTER)

## 构建要求

- Android SDK 21+
- Kotlin 1.9.10
- Gradle 8.1.2

## 编译安装

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```