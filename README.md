# CallDelegate Android

CallDelegate 是一个面向 Android 12 及以上系统的本地优先智能通话代接原型。项目使用 Kotlin 和 Jetpack Compose，包含音频采集、能量 VAD、Vosk 普通话 ASR、规则 NLU、JSON 对话状态机、sherpa-onnx VITS TTS、Room 本地记录以及设备适配代码。

## 主要部分

- Kotlin、Jetpack Compose、Hilt、Coroutines 和 StateFlow；
- 多模块 Clean Architecture，UI 通过领域接口访问实现；
- 16 kHz 单声道 PCM16 音频链路和能量 VAD；
- Vosk 离线普通话识别接入；
- 分层规则分类、正则实体抽取和 JSON 有限状态机；
- sherpa-onnx AISHELL-3 VITS 合成接入；
- Room、DataStore 和 WorkManager 本地数据管理；
- 模型包校验、导入、激活、回滚和设备资源分级；
- 单元测试、Android 仪器测试和可选的真机采集工具源码。

应用 Manifest 不申请 `android.permission.INTERNET`。Gradle 依赖下载和本地资源准备需要开发电脑联网，但应用运行时设计为本地处理。

## 仓库内容

```text
app/          应用入口、Compose 导航、Telecom 与系统适配
benchmark/    Macrobenchmark 配置
core/         音频、AI、规则、模型和公共基础
data/         Room、DataStore 与清理任务
domain/       领域模型和接口
feature/      Compose 页面与 ViewModel
test/         精简的文本评测语料和测试夹具
scripts/      构建检查与依赖准备脚本
tools/        模型包制作工具
docs/         架构、资源准备和限制说明
```

## 未包含的文件

- Vosk 和 VITS 模型权重；
- `sherpa-onnx-1.13.2.aar`；
- Shizuku Manager APK 和 scrcpy-server 二进制；
- APK、录音、日志、设备序列号和本机路径；
- WAV 回归、性能测试和真实设备原始结果。

对应来源、版本、SHA-256 和放置路径见 [本地资源准备](docs/LOCAL_ASSETS.md)。

## 环境要求

- Android Studio Ladybug 或更新版本；
- JDK 17；
- Android SDK Platform 35；
- Android SDK Build Tools 35.0.0；
- 运行设备为 Android 12（API 31）或更高版本。

不要在 `gradle.properties` 中提交个人 JDK 路径。请通过 Android Studio 的 Gradle JDK 设置或本机 `JAVA_HOME` 配置 JDK 17。

## 构建

首先下载并校验编译所需的 sherpa-onnx AAR：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup_sherpa_aar.ps1
```

然后构建 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

没有安装模型资源时，源码仍可编译，但真实 ASR/TTS 链路不能正常运行。完整运行前继续按照 [本地资源准备](docs/LOCAL_ASSETS.md) 放置模型。

## 测试

准备 AAR 后可运行 JVM 测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

仓库保留了规则测试需要的文本语料和精简 ASR 文本夹具。依赖历史真机产物的回放测试在数据不存在时会明确跳过；跳过不代表真机能力通过。

## 重要边界

- Telecom 或默认拨号器身份本身不提供运营商通话的双向 PCM；
- Shizuku/scrcpy 路径依赖 Android 版本、厂商 ROM、shell 权限和音频策略；
- 本地播放帧写入成功不能证明远端通话参与者实际听见 TTS；
- 模拟输入、JVM 测试、APK 构建和真实设备验收是不同证据层级；
- 通话录音和转写的告知义务必须按使用地区和实际用途评估。

更多说明见 [已知限制](docs/LIMITATIONS.md)。

## 文档

- [测试结果汇总](docs/TEST_RESULTS_SUMMARY.md)
- [架构说明](docs/ARCHITECTURE.md)
- [本地资源准备](docs/LOCAL_ASSETS.md)
- [模型导入格式](docs/MODEL_IMPORT.md)
- [已知限制](docs/LIMITATIONS.md)
- [依赖与许可证](docs/OPEN_SOURCE_LICENSES.md)

## License

项目源码使用根目录 [MIT License](LICENSE)。第三方依赖、模型和可选二进制仍受各自许可证约束；本仓库不因引用它们而改变其授权条件。
