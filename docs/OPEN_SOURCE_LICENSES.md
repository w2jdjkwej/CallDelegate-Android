# 依赖与许可证

项目源码使用根目录 `LICENSE` 中的 MIT License。第三方组件仍受各自许可证约束。

## 源码依赖

| 依赖族 | 用途 | 许可证 |
| --- | --- | --- |
| Kotlin、kotlinx.coroutines、kotlinx.serialization | 语言、异步和 JSON | Apache-2.0 |
| AndroidX、Jetpack Compose、Room、DataStore、WorkManager | Android UI 与基础设施 | Apache-2.0 |
| Dagger/Hilt | 依赖注入 | Apache-2.0 |
| Vosk Android | 离线 ASR 运行时 | Apache-2.0 |
| sherpa-onnx | 离线 TTS 运行时 | Apache-2.0 |
| Shizuku API/Provider | 用户授权的 shell 服务桥 | Apache-2.0 |
| JUnit 4 | 测试 | EPL-1.0 |
| Google Truth | 测试断言 | Apache-2.0 |

具体版本以 `gradle/libs.versions.toml` 为准。

## 未随仓库分发的资源

以下文件未提交，使用者需要自行从官方来源取得并遵守其许可证：

- sherpa-onnx Android AAR；
- Vosk 普通话模型；
- AISHELL-3 VITS 模型；
- Shizuku Manager APK；
- scrcpy-server。

来源和校验值见 `docs/LOCAL_ASSETS.md`。正式发布 APK 前还应检查传递依赖、模型、AAR、native 库及其 LICENSE/NOTICE，并生成发布版本对应的 SBOM 或等价清单。
