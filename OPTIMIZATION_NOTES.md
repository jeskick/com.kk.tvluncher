# 优化备忘（v1.1.8 之后观察期）

> 创建于 2026-04-18，v1.1.8 部署电视后待观察。
> 如果 2~3 天稳定运行没有明显问题，按「优先级高」的条目逐项落地。

---

## 一、主线程 / IO 现状核对（✅ 已确认无违规）

| 模块 | 调度器 | 文件:行 |
|---|---|---|
| `WeatherRepository.fetchWeather` | `withContext(IO)` | `app/src/main/java/com/kk/data/WeatherRepository.kt:40` |
| `AppRepository.getInstalledApps` | `withContext(IO)` | `app/src/main/java/com/kk/data/AppRepository.kt:14` |
| `SmbScanner.scan` | `withContext(IO)` | `app/src/main/java/com/kk/data/SmbScanner.kt:19` |
| MainViewModel slideshow 内部加载 | 外壳 Main + `withContext(IO)` | `app/src/main/java/com/kk/ui/MainViewModel.kt:140, 237` |
| MainActivity 背景下载 + 拼图 | `lifecycleScope.launch(Dispatchers.IO)` | `app/src/main/java/com/kk/ui/MainActivity.kt:469` |
| KeepAliveService 心跳 | `CoroutineScope(Dispatchers.IO)` | `app/src/main/java/com/kk/tvlauncher/service/KeepAliveService.kt:28` |

结论：**没有把网络/磁盘 IO 放到主线程**。`Glide.into()` 在 Main 线程调用是官方要求，内部自有线程池做解码，合法。

---

## 二、长期运行的潜在隐患（观察期结束后再改）

### 🟡 优先级高

#### 1. `SmbJImageFetcher` 的 `SMBClient` 单例永不关闭
- 位置：`app/src/main/java/com/kk/tvlauncher/data/SmbJImageFetcher.kt:31`
- 风险：NAS/路由重启后 client 内部 socket 半死状态，之后每次调用 connect timeout。
- 改法：捕获 `IOException` 时把引用置空 → 下次 lazy 重建。

#### 2. `MainActivity.onResume` 每次都 `viewModel.loadAll()`，无节流
- 位置：`app/src/main/java/com/kk/ui/MainActivity.kt:92`
- 风险：HOME 连按 / 熄屏醒来会重复发起 `listImages + weather + apps` 请求，费电费流量。
- 改法：
  - `loadWeather`：5 分钟内有缓存就复用
  - `loadApps`：监听 `ACTION_PACKAGE_ADDED/REMOVED` 广播驱动刷新，去掉 onResume 里的主动调用
  - `loadSmbSlideshow`：图片列表 TTL 10 分钟

### 🟡 优先级中

#### 3. `tryMergePortrait` 高频切换时 Bitmap 峰值偏高
- 位置：`app/src/main/java/com/kk/ui/MainActivity.kt:511`
- 风险：每次拼接新建 2× 原图 + 2× scaled (1920×1080) + 1× 合成，≈ 30–50 MB 临时占用。
- 改法：
  - 解码带 `inSampleSize` 预缩放（按 1920 目标估算采样率）
  - 合成后显式 `b1/b2.recycle()`（scaled 的不能 recycle，已被 Glide 持有）

### 🟢 优先级低（稳定性更多，性能影响不大）

#### 4. Glide 全局配置（未自定义）
- 目前用默认 LRU + 磁盘缓存，够用。如果内存吃紧可自定义 `GlideModule` 调小 `MemorySizeCalculator`。

#### 5. `HttpImageFetcher`/`WebDavImageFetcher` 未复用 connection
- 风险：每次 listImages 新建 `HttpURLConnection`，Android 默认 keep-alive 会缓解，一般无需优化。

---

## 三、已做得到位的优化（记录以免回退）

- `DockAdapter.cacheAssetIcons`：`assets.list("icons")` 只跑一次，不再每次 bind。
- `SmbJImageFetcher.client`：lazy 单例，不再每次调用都 new `SMBClient()`。
- `WifiLock/WakeLock`：`setReferenceCounted(false)` + 无超时，消除续期竞态。
- `AppPickerActivity`：用 `lifecycleScope`，避免 Activity 泄漏。
- `WeatherRepository`：gzip 解压 + stream 正确 close + `Accept-Encoding:gzip`。
- `LunarCalendar`：按天缓存 key，30s 时钟 Runnable 命中缓存。
- `KeepAliveService`：
  - 心跳改为主动 TCP connect（网关:53 + 本机:5555），穿透 NAT
  - 监听 `ACTION_SCREEN_ON/OFF`，熄屏心跳 30s → 10s

---

## 四、测试观察清单（这几天重点看）

- [ ] 熄屏 > 30 分钟后 `adb connect 192.168.8.223:5555` 能否立刻响应
- [ ] 壁纸切换频率是否稳定（按设置的秒数；不再出现「几秒就自动下一张」）
- [ ] HOME 单按切壁纸 / 双按呼出 Dock，是否灵敏不误触
- [ ] Dock 左右边界循环是否流畅
- [ ] 天气卡片能否默认加载成功（首次启动 + 重开）
- [ ] 长开 2~3 天后是否还能正常交互（内存/ANR）
- [ ] Clash 面板是否有异常出站（应只有正常出站流量，无心跳外连）
