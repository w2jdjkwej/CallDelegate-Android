# 本地资源准备

本仓库不提交模型权重、AAR、APK 或 scrcpy-server 二进制。下面记录当前源码对应的版本、来源、校验值和本地放置路径。

下载完成后应先核对 SHA-256。`.gitignore` 已排除这些文件，不要用 `git add -f` 强制提交。

## 1. sherpa-onnx Android AAR

| 项目 | 值 |
| --- | --- |
| 版本 | `1.13.2` |
| 官方来源 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar` |
| SHA-256 | `aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245` |
| 放置路径 | `core/ai/libs/sherpa-onnx-1.13.2.aar` |

Windows 可以直接运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup_sherpa_aar.ps1
```

脚本只在哈希匹配时写入最终文件；已有但哈希不匹配的文件不会被覆盖。

## 2. Vosk 普通话 ASR 模型

| 项目 | 值 |
| --- | --- |
| 模型 | `vosk-model-small-cn-0.22` |
| 官方来源 | `https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip` |
| 压缩包 SHA-256 | `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba` |
| 放置目录 | `app/src/main/assets/models/asr/vosk-model-small-cn-0.22/` |

解压后，该目录应至少包含 `am/`、`conf/`、`graph/` 和 `ivector/`。仓库保留的 `model_manifest.json` 用于逐文件完整性校验。

## 3. AISHELL-3 VITS TTS 模型

| 项目 | 值 |
| --- | --- |
| 模型 | `vits-icefall-zh-aishell3` |
| 官方来源 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2` |
| 压缩包 SHA-256 | `ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1` |
| 放置目录 | `app/src/main/assets/models/tts/vits-icefall-zh-aishell3/` |

运行时需要 `model.onnx`、`tokens.txt`、`lexicon.txt`、`speakers.txt` 和相应 FST 资源。仓库保留的 `model_manifest.json` 记录了必需文件及哈希。

## 4. 可选的 Shizuku/scrcpy 通话实验资源

这两项不是普通源码构建的必要条件；缺少它们时，对应实验入口不能工作。

| 资源 | 官方项目 | 本源码期望路径 | 当前源码固定 SHA-256 |
| --- | --- | --- | --- |
| Shizuku Manager 13.6.0 | `https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0` | `app/src/main/assets/third_party/shizuku/shizuku-v13.6.0.r1086.2650830c-release.apk` | `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f` |
| scrcpy-server 4.0 | `https://github.com/Genymobile/scrcpy/releases/tag/v4.0` | `app/src/main/assets/scrcpy-server-v4.0` | `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a` |

源码会校验固定哈希。其他版本不能仅靠改名替换，需要重新核对协议、许可证、运行行为并更新相应常量。

## 5. 构建与运行的区别

- 只有 sherpa-onnx AAR 是当前 Kotlin 编译的直接本地依赖；
- ASR/TTS 模型决定真实离线语音链路能否运行；
- Shizuku/scrcpy 二进制只服务于可选的真实 SIM 通话实验；
- APK 构建成功不代表模型、通话录音或远端 TTS 已通过真机验收。
