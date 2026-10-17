# 子进程声卡直出与杜比全景声直通（P4）

> 目标：**音频由 C++ 子进程直接驱动声卡**，并且在音轨本身是压缩码流时走
> **IEC 61937 码流直通** —— 把 E-AC-3 / AC-3 / TrueHD 压缩帧原样送到 HDMI，
> 由功放/回音壁的**硬件解码器**解出杜比全景声（Atmos）。
> 这一路我们自己一个样本都不解，所以对象音频（全景声）能完整保留下来。

相关代码：

| 文件 | 职责 |
| --- | --- |
| `native/host/src/audio_device.cpp` | 端点枚举与能力探测（`--list-audio`）：哪些端点认哪些 IEC 61937 格式 |
| `native/host/src/audio_sink.cpp` | WASAPI 输出：共享 / 独占 PCM / 直通三种形态 |
| `native/host/src/audio_spdif.cpp` | 直通链路：demuxer → FFmpeg `spdif` 复用器（IEC 61937 封装）→ 声卡 |
| `native/host/src/audio_chain.h` | 解码链路与直通链路的共同接口（上层主循环不关心是哪种） |
| `native/host/src/main.cpp` | 形态决策（推断音轨编码 → 选链路 → 不行就降级）与 `--spdif-dump` 自校验 |
| `src/main/java/dev/cineduct/host/HostSession.java` | 传 `--audio-mode/--audio-device`，读状态环里的形态字段 |

---

## 1. 三种输出形态（越往下越"直"，代价是控制能力越弱）

| 形态 | 怎么走 | 能得到什么 | 代价 |
| --- | --- | --- | --- |
| **共享模式**（兜底） | 系统混音器（`GetMixFormat` 决定格式），我们解成 S16 再转设备格式 | 永远能用 | 可能被重采样/上混下混；不是位完美 |
| **独占 PCM** | `AUDCLNT_SHAREMODE_EXCLUSIVE`，格式由我们指定，绕过混音器 | 位完美（bit-perfect），可 5.1/7.1 多声道直出 | 期间该设备上其它声音被静音；**全景声已被我们解成声道，回不去了** |
| **IEC 61937 直通** | 不解码：压缩帧经 FFmpeg `spdif` 复用器封装成 IEC 61937 突发送声卡 | **功放硬件解码，全景声（Atmos）/DTS:X 原样保留** | **码流不可修改 → 音量/静音/距离衰减全部失效**（由功放控音量） |

`auto`（默认）的决策顺序：**直通（音轨是 Dolby/DTS 码流且端点认那种链路格式）→ 共享模式**。
任一步失败都会记下真实原因并继续降级，播放永远不会"因为音频失败而没声"。

**为什么 auto 不主动用独占 PCM**：独占会独占整个端点，期间该设备上其它声音（**包括 Minecraft
自己的音效**）全部被静音。对普通音轨（AAC 等，直通不了）而言，独占只换来"位完美"、
代价却是游戏没声音 —— 这笔交易在游戏里不划算。所以只有显式 `/cineduct audio mode exclusive`
才会走独占 PCM；直通是另一回事，能直通才是值得独占的理由。

## 2. 直通的链路格式（关键：速率必须和突发周期吻合）

IEC 61937 的链路速率不是随便定的。FFmpeg `spdif` 复用器按**数据突发周期**补齐零填充，
所以只要格式参数与它的假设一致，**输出字节速率天然等于链路速率** —— 我们不需要任何节流。

| 音轨 | SubFormat GUID | 链路格式 | 突发 | 换算 |
| --- | --- | --- | --- | --- |
| E-AC-3（DD+，含 Atmos/JOC） | `0000000a-0cea-0010-8000-00aa00389b71` | **4× 内容采样率**（48k → 192kHz），2ch/16bit | 24576 字节 | 24576 ÷ 768000 B/s = **32ms** ✓ |
| AC-3 | `00000092-0000-0010-8000-00aa00389b71` | 内容采样率（48kHz），2ch/16bit | 6144 字节 | 6144 ÷ 192000 B/s = **32ms** ✓ |
| TrueHD / MLP（Atmos） | `0000000c-0cea-0010-8000-00aa00389b71` | 192kHz（44.1k 系内容为 176.4kHz），8ch/16bit | MAT 帧 61440 字节 | 3072000 B/s 链路 |
| DTS | `00000008-0000-0010-8000-00aa00389b71` | 内容采样率，2ch/16bit | — | — |

- GUID 与结构取值依据微软文档
  [Representing Formats for IEC 61937 Transmissions](https://learn.microsoft.com/en-us/windows/win32/coreaudio/representing-formats-for-iec-61937-transmissions)。
- **Atmos 不需要专门的"Atmos subtype"**：DD+ 里的 Atmos 就是同一条 E-AC-3 码流里的 JOC 元数据，
  接收端看到就切全景声解码。文档里那个 `..._DOLBY_DIGITAL_PLUS_ATMOS` 端点通常不报支持（本机也是），
  所以走普通 DD+ subtype 才是通用做法。
- 封装细节（FFmpeg `libavformat/spdifenc.c`）：写 `0xF872/0x4E1F` 前导 + 数据类型 + 长度，
  载荷按 **MSB-first 的 16 位字**做字节交换（IEC 60958 链路要求的内存表示），最后补零到突发长度。
  自己实现最容易错的就是这两处，所以直接用它的复用器。

## 3. 音量在直通模式下不可用（设计上无法避免）

码流一个字节都不能改，改了就解不出来。因此：

- `/cineduct screen volume`、距离衰减（`rangeMin/rangeMax`）、MC 主音量**在直通会话上全部无效**；
- 客户端**不会再每帧推音量**（`MediaSession.setVolumePercent/setMuted` 会去重，并且直通时直接跳过）——
  否则每块屏每帧都要走一次同步管道往返；
- 状态环里带 `audioMode`，附属 mod 据此把音量显示成"由接收端控制"；
- 想恢复音量控制：`/cineduct audio mode exclusive`（位完美但无 Atmos）或 `shared`。

## 4. 端点能力探测与自校验（都不需要功放）

```powershell
# 列出所有播放端点，并逐一试探"独占 PCM"与"IEC61937 直通"能力
cineduct-host.exe --list-audio

# 把封装好的 IEC 61937 码流落盘，并**自校验**：
#   ① 用 FFmpeg 的 spdif 解复用器把文件解回压缩帧（反解字节序/前导）
#   ② 解码这些帧，与"直接解码原始音轨"的样本数对比
cineduct-host.exe --ffmpeg-dir <DLL目录> --url <eac3文件> --spdif-dump out.spdif
```

本机实测（RTX 5060 Ti + 默认 HDMI 端点）：

```
[host] 已写出 IEC 61937 码流 52420608 字节（文件 52420608 字节），源编码 eac3
[host] 经 spdif 解回: eac3 6ch 48000Hz，3276288 帧 → 68256ms
[host] 直接解原始音轨: eac3 6ch 48000Hz，3277056 帧 → 68272ms
[host] 结论: ✔ 码流封装通过 —— 突发/字节序/填充都正确（差值 -16ms，容差 100ms）
```

52420608 ÷ 68.272s = **767999 B/s** ≈ 768000 B/s（= 192kHz × 4 字节）—— 速率精确吻合。

## 5. 踩过的两个 Windows 坑（别再踩）

### 5.1 `hnsPeriodicity` 必须等于 `hnsBufferDuration`

`AUDCLNT_STREAMFLAGS_EVENTCALLBACK` + `AUDCLNT_SHAREMODE_EXCLUSIVE` 时，
官方文档明确要求 `hnsPeriodicity` **非零且等于 `hnsBufferDuration`**。传 0 会返回
`AUDCLNT_E_BUFFER_SIZE_ERROR (0x88890016)` —— 这个名字看起来像"缓冲时长不合法"，
于是很容易去反复调整时长（我们试过 10/20/40/50/80/100ms，结果完全一样），
真凶却在 periodicity 参数上。共享模式反过来要求它是 0。

### 5.2 显卡 HDMI 端点：事件驱动的独占流不推进 → 默认改推模式

现象极具误导性：`Initialize` 成功、`Start` 成功、`IsFormatSupported` 也说支持，
但**设备时钟永远 0、`GetCurrentPadding` 恒等于满缓冲**（一个字节都没被取走）。
同一份代码在 USB 耳机端点上完全正常 —— 所以问题在驱动/端点，不在我们的调用。

实测结论：

| 端点 | 独占 + 事件回调 | 独占 + 推模式（poll） |
| --- | --- | --- |
| `COLOGCS-1 (NVIDIA High Definition Audio)`（HDMI，默认） | ✘ 不推进 | ✔ 正常 |
| `Q27G40ZE (NVIDIA High Definition Audio)`（HDMI） | ✘ 不推进 | （未单独复测，同驱动） |
| `扬声器 (HECATE G2 GAMING HEADSET)`（USB） | ✔ 正常 | ✔ 正常 |

因此独占/直通**默认走推模式**（`MEDIAFIX_AUDIO_POLL=0` 可强制回事件驱动）。
代价只是一条 5ms 轮询循环，对视频播放完全无感（延迟本来就有几十毫秒、画面又跟着音频时钟走）。

### 5.3 直通的独占缓冲必须能装下**一个完整突发**

DD+ 的突发是 24576 字节。若缓冲小于它（例如按设备周期取 10ms → 1920 帧 = 7680 字节），
驱动拿不到完整突发就一个字节都不送 —— 现象和 5.2 一模一样，很难区分。
所以直通的缓冲固定从 **100ms** 起试。

### 5.4 独占模式不要用 `AUDCLNT_BUFFERFLAGS_SILENT` 预填

那是"告诉音频引擎这块是静音、别读"的共享模式语义。独占模式下缓冲就是硬件缓冲，
预填静音改为**写真实零**更稳（我们最初在独占流上也用了 SILENT 标志）。

### 5.5 某些显卡 HDMI 端点上"独占模式"会让 Windows 音频引擎崩溃（本机实测）

这是把直通接进游戏后遇到的最狠的一个坑，也是最容易误判成"我们的 bug"的那种。

**现象**：子进程起来后一切正常（Initialize/Start 都成功、码流封装正确），
但 **2~5 秒后进程整个消失**；玩家侧表现为"没有声音、画面只剩 1~2 帧"。
Windows 事件日志给出真凶：

```
Faulting application name: cineduct-host.exe
Faulting module name: audiokse.dll (Windows 音频引擎的 KSE 层)
Exception code: 0xc0000005        ← 访问违例
```

**判别实验**（同一份代码、同一个素材，只换端点/形态）：

| 端点 | 输出形态 | 结果 |
| --- | --- | --- |
| HDMI（NVIDIA，默认） | 独占 PCM | ✘ 4.6 秒崩（`audiokse.dll`） |
| HDMI（NVIDIA，默认） | IEC 61937 直通 | ✘ 2.5~5 秒崩（同一个模块） |
| HDMI（NVIDIA，默认） | 共享模式 | ✔ 30 秒正常 |
| USB 耳机 | 独占 PCM | ✔ 30 秒正常 |

结论：**崩的不是码流、也不是我们的调用，而是该端点在 WASAPI 独占模式下的驱动/系统路径**。
直通必须独占 → 所以在那个端点上直通是无法工作的（换端点即可，码流本身已用
§4 的解复用+解码回路证明过是对的）。

**已加的三道防护**（都在"音频出问题也不能牵连画面/不能反复崩"这个原则上）：

1. **音频失败不得冻结画面**：音频链出错、声卡写入连续失败、或音频时钟停滞超过 3 秒，
   子进程都会**永久改用挂钟节奏**继续出画面（以前这里只是每帧等一次 3 秒兜底，
   表现就是"画面停在 1~2 帧"，非常像死锁）。
2. **崩在独占/直通上就自动降级**：Java 侧发现子进程非 0 退出、且当时用的是独占/直通音频，
   就把本次会话改成共享模式再重启（有声音、没有全景声直通），并给玩家一条一次性提示。
   避免在同一个端点上反复把音频引擎搞崩。
3. **卡死能被发现**：客户端 8 秒没有新帧就判定卡死并重启。
   注意 `pendingSeekMs >= 0` 原本会让卡死判定**永久跳过**（seek 之后子进程死锁就再也不会自愈），
   现在只给 seek 20 秒宽限期。

### 5.6 seek 必须是"请求"，不能是视频线程同步等待

子进程里视频线程与音频线程是两条独立的线程，而 FFmpeg 的 demuxer/解码器/复用器
**不是线程安全的**。早先的实现是视频线程直接在主循环里对音频链做 `av_seek_frame`
（并重建 spdif 复用器），一旦音频线程正卡在声卡背压里（暂停时最典型：环形缓冲不再排空），
seek 就会**永久阻塞**，整个子进程跟着死掉 —— 现象同样是"没声音 + 画面停在 1~2 帧"。

现在的约定：

* `AudioChain::seek()` **只登记一个请求**（原子变量），立即返回；
* 真正的定位由各自的**工作线程**在每轮开头执行（`AudioSource::applySeek` / `AudioBitstream::applySeek`），
  于是 FFmpeg 上下文始终只有一个线程在碰；
* 上层 seek 后照常 `AudioSink::flush()` —— 它清空环形缓冲，顺便**解除**工作线程在背压上的阻塞，
  定位随即执行。

`native/eof-test.ps1` 里专门加了"暂停 + seek"这一步来守住它。

### 5.7 怎么"程序化"判断到底有没有在输出杜比全景声

用户没法靠耳朵可靠区分"真 Atmos"和"普通多声道混音"，但 Windows 其实把答案放在官方 WinRT 接口里：
**`Windows.Media.Audio.SpatialAudioDeviceConfiguration`**（`GetForDeviceId(端点ID)`）。

| 属性 | 含义（官方文档原话） |
| --- | --- |
| `IsSpatialAudioSupported` | 该端点**能不能**做空间音效 |
| `DefaultSpatialAudioFormat` | **用户选择**的空间音效格式（"the format the user has selected"） |
| `ActiveSpatialAudioFormat` | **系统当前实际在跑**的格式（"could have been changed by the system"；未激活时返回全零 GUID） |

格式名不硬编码：读 `SpatialAudioFormatSubtype` 的静态常量
（`DolbyAtmosForHomeTheater` / `DolbyAtmosForHeadphones` / `DolbyAtmosForSpeakers` /
`WindowsSonic` / `DTSHeadphoneX`）与读到的 GUID 比对，翻成人话。

实现：子进程里为这一处查询**手写最小 ABI**（IID 与虚表顺序抄自 SDK 的
`winrt/windows.media.audio.h`），只链接 `runtimeobject.lib`，不引入 C++/WinRT。
结果写进共享内存（`spatialAudio`：0=支持但未启用 / 1=已启用 / 2=查不到 / 3=端点不支持，
`spatialFormat`：格式名），客户端**零 IPC** 读取，并在游戏聊天框给本机玩家播报一次
（`displayClientMessage` 是本机消息：不发服务器、别的玩家看不到）。
`/cineduct audio` 与 `cineduct-host.exe --list-audio` 也会显示同样的信息。

> 顺带一个坑：端点属性 `PKEY_AudioEnvironment_SpatialAudioActive` **在端点属性库里读不到**
> （它属于"音频环境 APO 通知"那套，由 APO 上报，本机三个端点全都没有），所以只能走上面的 WinRT 接口。

## 6. 配置与指令

```jsonc
// <游戏目录>/cineduct-ffmpeg.json
{
  "audioMode": "auto",   // auto | passthrough | exclusive | shared
  "audioDevice": ""      // "" = 系统默认；"@3" = --list-audio 的编号；其它 = 设备名子串
}
```

```
/cineduct audio                                  # 看当前形态、端点、内容/链路采样率、声道数
/cineduct audio mode passthrough                 # 切形态（会重建会话 —— 画面会重来一下）
/cineduct audio mode shared                      # 没声音/不想被独占静音时用这个
/cineduct audio device COLOGCS                    # 换端点（名字子串，重名时用 "名字#序号"）
/cineduct audio device @1
```

子进程参数（一般不需要手写，`HostSession` 会传）：`--audio-mode`、`--audio-device`、
`--list-audio`、`--spdif-dump`。

## 7. 状态环（v3 追加字段）

| 偏移 | 字段 | 含义 |
| --- | --- | --- |
| 224 | `audioMode` | 0=共享 1=独占PCM 2=IEC61937直通 |
| 228 | `audioChannels` | 输出声道数（直通时是链路声道数：DD+ 为 2、TrueHD 为 8） |
| 232 | `audioContentRate` | 解码后的内容采样率（直通时链路是 4× 内容或 192k，这个才是音轨本身的） |

Java 侧：`HostSession.audioModeName()/volumeLocked()/audioChannels()/audioContentRate()`，
`MediaSession.audioModeName()/volumeLocked()`，
附属 mod 侧：`ClientScreenStatus.audioMode()/volumeLocked()/passthroughAudio()`。

## 8. 验证矩阵（本机实测）

| 项目 | 结果 |
| --- | --- |
| IEC 61937 封装正确性（spdif 解复用 + 解码回路） | ✔ 差值 -16ms / 68272ms |
| 输出速率 = 链路速率 | ✔ 767999 B/s vs 768000 B/s |
| 直通链路实时推进（`--audio-only`） | ✔ 链路已写 1000ms ≈ 挂钟 1000ms |
| 端点能力探测（20 个端点） | ✔ 默认 HDMI 端点报 DD+/AC-3/TrueHD 直通可用 |
| 独占 PCM（HDMI，推模式） | ✔ 设备时钟 499→1012→1525ms 稳定推进 |
| 共享模式（原路径） | ✔ 不受影响 |
| 视频 + DD+ 音轨（真实影片形态）共享模式 25 秒 | ✔ 稳定（片尾进入待命） |
| 独占模式 30 秒（HDMI） | ✘ 崩在 audiokse.dll（见 §5.5） |
| 独占模式 30 秒（USB 耳机） | ✔ 正常 |

**仍需在真实功放上确认的**：接收端是否真的点亮 Dolby Atmos 灯（本机默认端点是
`COLOGCS-1`，如果它只是显示器/不支持 DD+，直通会静音 —— 此时用
`/cineduct audio mode shared` 即可回到有声状态）。
