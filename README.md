<div align="center">
  <img src="assets/icon.svg" width="128" height="128" alt="SrunX" />
  <h1>SrunX</h1>
  <p>极简轻量的开源深澜（Srun3000）校园网 Android 自动认证与保活客户端。</p>
</div>

## 界面预览

<img width="1200" height="2000" alt="image" src="https://github.com/user-attachments/assets/4e7989c6-e7e5-4e32-b6e5-05af076904a3" />

## 核心特性

- **双进程轻量化架构**：界面（主进程）与后台保活（`:bg` 独立无界面进程）分离，通过原生 `Messenger` 跨进程通信；划掉界面卡片后 `:bg` 不加载 UI 渲染资源，独立极简驻留。
- **开着 5G / VPN 也能直接认证**：通过 `ConnectivityManager` 获取 Wi-Fi `Network` 实例，使用 `wifiNetwork.openConnection(url)` 强制认证流量直出 Wi-Fi 网卡，无需手动关闭移动数据，也不干扰其他应用。
- **彻底免定位权限**：不请求任何位置、存储或悬浮窗权限；仅在 Wi-Fi 处于未验证 / 门户登录状态时短超时探测深澜网关并自动完成登录，在家或宿舍连普通 Wi-Fi 时不会误触发。
- **Android 15 兼容后台保活**：采用 `specialUse` 前台服务 + 开机自启 + `IMPORTANCE_MIN` 隐身通知（状态栏无多余图标）+ 双快捷磁贴（`SrunX 保活` 开关监听 + `SrunX 登录` 一键认证）。
- **零第三方网络库**：纯 Kotlin + 原生 `HttpURLConnection` 实现 `srun_bx1` 协议（`xEncode`、自定义 Base64、`HMAC-MD5`、`SHA1`），支持可选运营商域后缀与 `ac_id` 留空自动探测。
- **本地密钥安全存储**：密码经 Android Keystore（AES-256-GCM）加密后存储在本地 DataStore，关闭系统备份（`allowBackup="false"`），无任何遥测或第三方联网。

## 权限说明

- `INTERNET` / `ACCESS_NETWORK_STATE`：与用户配置的校园网网关通信及监听 Wi-Fi 状态。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：保持后台监听服务存活。
- `RECEIVE_BOOT_COMPLETED`：按用户开关在开机后恢复自动监听。
- `POST_NOTIFICATIONS`：展示可折叠的前台服务状态通知（Android 13+ 可选）。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：引导用户在系统设置中关闭电池优化以防杀后台。

## 使用方式

1. 打开 SrunX，填写校园网认证网关地址（如 `http://10.0.0.1`）、学号、密码；`ac_id` 与运营商域后缀按需选填后点击「保存配置」。
2. 保持「Wi-Fi 自动监听认证」开启，建议点击「去设置」关闭系统电池优化。
3. 可在手机下拉控制中心编辑添加 **SrunX 保活**（启停后台监听与查看存活状态）和 **SrunX 登录**（直接绑 Wi-Fi 认证）快捷磁贴。
