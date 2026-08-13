# 模型制作、导入与恢复

设置页只接受本地 `.zip`。文件先复制到 App 缓存，再完整校验并解压到私有暂存目录；只有全部成功后才原子更新活动模型指针。校验失败不会删除或覆盖旧模型。

## ZIP 根目录

```text
model.zip
├── model_manifest.json
├── model.onnx
└── tokens.txt
```

不允许 ZIP 中出现绝对路径、`..`、重复条目或未批准扩展名。压缩包上限 450MB，解压总量上限 500MB，模型清单上限 1MB。

## 清单示例

```json
{
  "schemaVersion": 1,
  "type": "ASR",
  "displayName": "Vosk Mandarin Small",
  "version": "1.0.0",
  "cpuArchitecture": "arm64-v8a",
  "estimatedMemoryMb": 320,
  "runtime": "vosk",
  "license": "Apache-2.0",
  "sampleRateHz": 16000,
  "files": [
    {
      "path": "model.onnx",
      "sha256": "64位十六进制SHA256",
      "required": true,
      "role": "MODEL"
    },
    {
      "path": "tokens.txt",
      "sha256": "64位十六进制SHA256",
      "required": true,
      "role": "TOKENS"
    }
  ]
}
```

支持类型：`VAD`、`ASR`、`INTENT`、`ENTITY`、`TTS`。清单本身是必须校验的配置文件；它还必须声明运行时、许可证和采样率。ASR 必须有 `TOKENS` 或 `VOCAB`；TTS 必须有 `TOKENS`、`LEXICON` 或 `VOCAB`。单模型以及全部活动模型合计的预计内存上限均为 1200MB，为应用本身和音频缓冲预留约 300MB；这是导入保护阈值，不是实测峰值承诺。

辅助打包脚本：

```bash
python tools/create_model_package.py \
  --type ASR \
  --name "Mandarin ASR" \
  --version 1.0.0 \
  --memory-mb 320 \
  --runtime sherpa-onnx \
  --license Apache-2.0 \
  --model /path/model.onnx \
  --tokens /path/tokens.txt \
  --output mandarin-asr.zip
```

`--model` 也可指向 Vosk 模型目录；脚本会递归保留相对路径，并把 `words.txt`/`tokens.txt` 标记为词表、`.conf`/`.json` 标记为配置资源。

“恢复内置 Mock”只切换活动指针，不会在校验失败时破坏当前可用模型。导入成功只证明包结构、资源预算和哈希符合要求，不等于模型精度、设备兼容性或推理性能已经通过验收。
