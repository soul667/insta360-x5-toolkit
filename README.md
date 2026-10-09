# insta360-x5-toolkit

**Linux 双 Insta360 X5 同步控制工具箱** — 多机同步录制、X5 BLE 协议逆向文档、网页控制台、时间码对齐与误差校验、WiFi 素材拉取。

> 实测环境：两台 Insta360 X5（2026-10 固件），Linux + BlueZ。双机命令派发时间差 **≤ 50µs**，双机录制稳定落盘。
>
> 非官方项目，与影石（Insta360）无关联；逆向工作仅为互操作性（控制自有设备）。使用风险自负。

[English](#english) · [协议规范](docs/x5-ble-protocol.md) · [时间码与误差校验](docs/sync-verification.md)

---

## 这是什么

Insta360 X5 的 BLE 直控协议与旧机型（X3/X4 的 16 字节头）**完全不同**，上游开源工具声称的 X5 支持实际未经验证——裸命令会被相机直接无视。本项目在真机上完成了：

1. **逆向 X5 的 BLE 协议**：与 WiFi/TCP 控制协议同款的"网络帧"（4 字节长度前缀 + 类型字节 + 12 字节消息头 + protobuf），含 `syNceNdinS` 握手、1Hz 心跳、HTTP 风格应答、文件名回执；
2. **单进程多机同步控制**：连接一次、保持多路 BLE，屏障式同时发令并测量派发差/RTT；
3. **网页控制台**：浏览器点击"开始录制/停止录制"，自动显示两台文件名与时钟差；
4. **时间码对齐工具链**：官方校准路径说明 + OSC 写时钟脚本 + 拍手素材帧偏移校验。

## 组件

| 功能 | 说明 | 位置 |
|---|---|---|
| 多机同步控制 | `multi run` / `multi shell`：单进程保持多台连接，同步发令，输出派发差与 RTT | `patches/` |
| X5 网络帧协议 | `ProtoFormatNetwork` 实现（发送/接收/重组/应答匹配/心跳/握手） | `patches/` |
| 网页控制台 | `multi serve`：录制/停止/拍照/标记，实时显示时钟差与录制文件名 | `patches/` |
| 协议探针 | `x5probe`：原始帧收发与通知解析（继续逆向用） | `patches/` |
| 帧偏移校验 | 拍手/瞬态事件 → 毫秒/帧级偏移 | `tools/sync_check.py` |
| 相机时钟读写 | 通过官方 OSC API 读取/尝试写入 `_dateTime` | `tools/osc_settime.py` |
| 双机时钟校准 | 一键：开热点 → 连 AP → 校准 → 恢复网络 | `tools/osc_timesync.sh` |
| 素材拉取 | 通过相机 WiFi/OSC 拉取最近录像到本机 | `tools/fetch_clips.sh` |

## 快速开始

### 1. 构建增强版 insta360ctl

上游 [xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl) 未附许可证，本项目以 6 个补丁的形式提供修改：

```bash
git clone https://github.com/xaionaro-go/insta360ctl.git
cd insta360ctl
git am /path/to/insta360-x5-toolkit/patches/*.patch
go build -o bin/insta360ctl ./cmd/insta360ctl/
```

> 补丁内容见 [patches/README.md](patches/README.md)。

### 2. 扫描相机

```bash
sudo bin/insta360ctl scan --timeout 10s
#   [1] X5 ABCDEF [AA:BB:CC:DD:EE:FF] (X5, RSSI: -35)
#   [2] X5 GHIJKL [11:22:33:44:55:66] (X5, RSSI: -45)
```

### 3. 双机同步录制

**实测设备（本项目使用的两台 X5）**：

| 相机 | BLE 地址 |
|---|---|
| X5 53F3TQ | `f0:23:ae:62:6c:a0` |
| X5 6JDF89 | `f0:23:ae:76:ef:6e` |

连接这两台的完整命令：

```bash
# 1) 交互式会话：两台同时开录/停止，打印派发差与文件名
sudo bin/insta360ctl multi shell --addr f0:23:ae:62:6c:a0 --addr f0:23:ae:76:ef:6e

# 2) 网页前端：浏览器里点击"开始录制/停止录制"
sudo bin/insta360ctl multi serve --addr f0:23:ae:62:6c:a0 --addr f0:23:ae:76:ef:6e
```

#### 启动网页前端（multi serve）

```bash
sudo bin/insta360ctl multi serve --addr f0:23:ae:62:6c:a0 --addr f0:23:ae:76:ef:6e
# 输出: Dual-camera web console: http://127.0.0.1:8787
```

然后用浏览器打开 **http://127.0.0.1:8787**。页面功能：

| 元素 | 说明 |
|---|---|
| ● 开始录制 / ■ 停止录制 | 两台相机同步触发；录制中"开始"自动禁用 |
| 📷 拍照 / 🚩 高亮标记 | 双机同步执行 |
| 启动延迟（秒） | 例如填 3：3 秒后触发，方便先摆好手 |
| 每次操作显示 | 每台的派发偏移、RTT、成功/失败；**停止时显示各自文件名和两台时钟差** |
| 状态 | 每秒自动刷新，顶部显示已连接相机列表 |

常用参数：

- 手机/平板访问：`--listen 0.0.0.0:8787`（局域网内可见，无鉴权，谨慎使用）
- 端口被占用：`--listen 127.0.0.1:8788`
- 该命令是常驻进程：**保持终端不要关**；结束用 `Ctrl+C`
- 相机无操作数分钟会自动关机，届时前端会显示失败——重新开机相机后重启 `multi serve` 即可

#### 通用示例（地址换成你自己的）

```bash
# 交互式会话（推荐；连接只建立一次，后续发令抖动最小）
sudo bin/insta360ctl multi shell --addr AA:BB:CC:DD:EE:FF --addr 11:22:33:44:55:66
x5> start          # 两台同时开录
x5> stop           # 同时停止；会打印各自刚录制的文件名
x5> raw 0F         # 查询录制状态（0x0F，实测可用）
x5> quit

# 一次性命令（脚本/自动化友好）
sudo bin/insta360ctl multi run --addr AA:BB --addr CC:DD --delay 5s record start
sudo bin/insta360ctl multi run --addr AA:BB --addr CC:DD --no-wait record stop

# 网页控制台：浏览器里点击按钮
sudo bin/insta360ctl multi serve --addr AA:BB --addr CC:DD
# 打开 http://127.0.0.1:8787 （手机访问加 --listen 0.0.0.0:8787）
```

实测输出示例（真实运行数据）：

```
=== record start ===
  X5 ABCDEF    AA:BB:CC:DD:EE:FF  dispatch +0s        rtt 104.7ms   ok
  X5 GHIJKL    11:22:33:44:55:66  dispatch +0s        rtt  86.9ms   ok
  -- cameras: 2, failed: 0, dispatch spread: 0s, max rtt: 104.7ms

=== record stop ===
  X5 ABCDEF    AA:BB:CC:DD:EE:FF  dispatch +0s        rtt 1.7805s   ok
      | file: ./DCIM/Camera01/VID_20261009_151104_00_058.insv
  X5 GHIJKL    11:22:33:44:55:66  dispatch +0s        rtt 1.6705s   ok
      | file: ./DCIM/Camera01/VID_20261009_151100_00_029.insv
  -- cameras: 2, failed: 0, dispatch spread: 0s, max rtt: 1.7805s
```

- **dispatch spread**：两台相机被写入命令的时间差（软件可控部分；实测 0.003–0.05 ms）
- **rtt**：命令确认往返；`stop` 的 ~1.7s 是相机在封装文件
- 文件名时间戳（`VID_YYYYMMDD_HHMMSS`）即相机时钟，可直接对比两台时钟差（秒级）

### 4. 时间码对齐与误差校验

X5 原生支持 Timecode（写入视频 Timecode Track）。推荐流程：

1. **时钟对齐**（三选一）：
   - 相机菜单 Timecode → "Sync system time"，分别连 Insta360 App 点刷新（官方，最稳）；
   - 外接音频 LTC 时间码发生器（经 Mic Adapter，帧级最准，官方兼容 Tentacle Sync / Atomos / Easync）；
   - `tools/osc_timesync.sh`（软件路线，通过 OSC 写 `_dateTime`，可写性视固件而定）。
2. **同步录制**：用 `multi shell` 同步开录。
3. **误差校验**：录制时在双机前拍一次手，导出视频后：

```bash
python3 tools/sync_check.py A.mp4 B.mp4 --fps 30
# offset (B-A): +137.21 ms = +4.12 frames @ 30fps
```

偏移稳定 → 后期统一校正；漂移 → 逐条测。详见 [docs/sync-verification.md](docs/sync-verification.md)。

### 5. 拉取素材

```bash
# 相机需在 BLE 广播中（未自动关机）；脚本会开热点、下载、恢复网络
./tools/fetch_clips.sh 2      # 每台拉最近 2 个文件到 ./clips/
```

## 已知限制

- **未实现的 X5 查询/设置**：电量、存储、设备信息、拍摄模式切换、HDR 开关。对应命令码在 X5 上语义不同或需要 protobuf 请求体；代码显式拒绝而不是乱发（`mode`/`hdr` 会报"暂不支持"）。
- **相机自动关机**：X5 无操作数分钟自动关机，关机后 BLE/WiFi 全断，**无法远程唤醒**。建议在相机设置里调长自动关机，或保持 USB 供电。
- **`.insv` 私有容器**：播放/导出需要 Insta360 Studio（ffmpeg 不能直接解码）。
- **固件差异**：逆向基于 2026-10 的 X5 固件；其他版本行为可能不同。
- **系统依赖**：BLE 需要 BlueZ；`fetch_clips.sh` 需要 NetworkManager（nmcli）；`sync_check.py` 需要 numpy + ffmpeg。

## 目录结构

```
insta360-x5-toolkit/
├── README.md
├── LICENSE
├── patches/                  # 上游 insta360ctl 的增强补丁（git am 应用）
├── docs/
│   ├── x5-ble-protocol.md    # X5 网络帧协议规范（含真实抓包）
│   └── sync-verification.md  # 时间码对齐与帧偏移校验方法
└── tools/
    ├── sync_check.py         # 帧偏移测量
    ├── osc_settime.py        # OSC 时钟读写
    ├── osc_timesync.sh       # 双机时钟一键校准
    └── fetch_clips.sh        # WiFi 素材拉取
```

## English

**Linux toolkit for synchronized dual Insta360 X5 control.** The X5 does **not** use the
legacy 16-byte header protocol; it speaks the WiFi/TCP control protocol framing over BLE
(4-byte length prefix + packet type + 12-byte message header). This toolkit contains:

- `patches/` — enhancements to [xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl):
  a verified X5 network-framed protocol implementation, single-process multi-camera
  synchronized dispatch (`multi run` / `multi shell`, measured dispatch spread ≤ 50µs),
  a local web console (`multi serve`), and an experimental protocol probe (`x5probe`).
- `docs/x5-ble-protocol.md` — the reverse-engineered protocol specification with raw captures.
- `docs/sync-verification.md` — timecode alignment and frame-offset verification methodology.
- `tools/` — frame-offset checker (`sync_check.py`), OSC clock read/write (`osc_settime.py`),
  two-camera clock calibration (`osc_timesync.sh`), clip download over WiFi (`fetch_clips.sh`).

Build: apply the patches to a fresh clone of the upstream repo (`git am patches/*.patch`),
then `go build ./cmd/insta360ctl`. Run `scan`, then `multi shell --addr <A> --addr <B>`
or `multi serve` for the web console.

Unofficial project, not affiliated with Insta360. Reverse engineering was done solely for
interoperability with hardware we own. Use at your own risk. MIT licensed (see LICENSE).

## Roadmap / Contributing

- 补齐 X5 电量/存储/设备信息查询（需要 Options 的 protobuf 请求体）
- 拍摄模式/HDR 的正确命令码
- 时间码校准命令的抓包与实现（App 流量逆向）
- 欢迎 PR / issue（附抓包时请打码序列号）

## License

[MIT](LICENSE)。上游 `insta360ctl` 未附许可证；本项目通过补丁方式提供修改。
