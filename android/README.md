# X5 双机遥控（Android）

用手机同步控制两台 **Insta360 X5** 的安卓 App：点"开始录制"两台同时开录，点"停止"同时停止，并显示各自录制的文件名与两台时钟差。

App 内置本项目逆向的 X5 BLE **网络帧协议**（见 [insta360-x5-toolkit](https://github.com/soul667/insta360-x5-toolkit) 的 `docs/x5-ble-protocol.md`），**通过蓝牙直连相机**——不需要 WiFi、不需要官方 App、不需要 Root。

## 功能

| 功能 | 说明 |
|---|---|
| 扫描 / 连接 | 自动发现 BLE 名称以 `X5 ` 开头的相机，可多选（支持两台同时连接） |
| 同步录制 | ● 开始录制 / ■ 停止录制；屏障式同时派发，显示每台派发偏移与 RTT |
| 拍照 / 高亮标记 | 双机同步执行 |
| 录制凭证 | 停止时显示相机回传的文件名（`VID_YYYYMMDD_HHMMSS...insv`） |
| 时钟差 | 从两台文件名时间戳自动计算（秒级，用于时间码对齐参考） |
| 启动延迟 | 可设 0–99 秒，方便摆好手再触发 |

## 构建

需要 JDK 17 + Android SDK（compileSdk 35）。命令行构建：

```bash
# 在 Android Studio 中直接打开本目录也可以
./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
```

无 Android Studio 时的最小环境（Linux，无 sudo）：

```bash
# 1) JDK 17（任意发行版）
# 2) Android cmdline-tools + SDK 组件
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
# 3) 指向 SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties
# 4) 构建
./gradlew assembleDebug
```

## 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

或把 APK 传到手机直接安装（需允许"安装未知应用"）。

## 使用

1. 两台 X5 **开机**（X5 无操作数分钟会自动关机；关机后 BLE 断开，App 会显示连接失败）；
2. 打开 App → **扫描相机**（首次会请求蓝牙权限）；
3. 勾选两台相机 → **连接所选**（等待显示"就绪 2/2"）；
4. 需要时先设"启动延迟"，然后 **● 开始录制** / **■ 停止录制**；
5. 停止后可以看到文件名和两台时钟差（时间码对齐时把这个差值纳入后期校正）。

> 建议：录校验素材时在双机前拍一次手，导出视频后用 `insta360-x5-toolkit` 里的 `sync_check.py` 量出帧级偏移。

## 技术栈（开源项目）

- **Jetpack Compose**（Google，Apache-2.0）— 全部 UI
- **kotlinx.coroutines**（JetBrains，Apache-2.0）— 并发与屏障式同步派发
- **AndroidX Activity / Lifecycle**（Apache-2.0）
- **Android 平台 BLE API**（`BluetoothGatt`）— 零第三方 BLE 依赖，构建最稳
  - 如需更"库化"的实现，可替换为 [Kable](https://github.com/JuulLabs/kable) 或 [Nordic Android BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library)（都是 Apache-2.0）

## 已知限制

- X5 上暂不支持通过 BLE 查询电量/存储、切换拍摄模式、开关 HDR（协议未映射，见 toolkit 文档）；
- 时间码校准需用官方 App 或外接 LTC（本 App 只显示时钟差，不写时钟）；
- 逆向基于 2026-10 的 X5 固件；其他固件版本行为可能不同。

## 免责声明

非官方项目，与影石（Insta360）无关联；逆向工作仅为互操作性（控制自有设备）。使用风险自负。

## License

MIT
