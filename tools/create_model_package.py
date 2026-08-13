#!/usr/bin/env python3
"""Create a CallDelegate-compatible model ZIP with SHA-256 metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import zipfile


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--type", required=True, choices=["VAD", "ASR", "INTENT", "ENTITY", "TTS"])
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--memory-mb", type=int, required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--license", required=True)
    parser.add_argument("--sample-rate", type=int, default=16000)
    parser.add_argument("--model", type=pathlib.Path, required=True)
    parser.add_argument("--tokens", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()

    if not args.model.exists():
        raise SystemExit(f"Missing model path: {args.model}")
    if args.model.is_dir():
        resources = []
        for path in sorted(p for p in args.model.rglob("*") if p.is_file()):
            relative = path.relative_to(args.model).as_posix()
            role = "MODEL"
            if path.name in {"tokens.txt", "words.txt"}:
                role = "VOCAB"
            elif path.suffix.lower() in {".conf", ".json"}:
                role = "CONFIG"
            resources.append((path, role, relative))
    else:
        resources = [(args.model, "MODEL", args.model.name)]
    if args.tokens:
        resources.append((args.tokens, "TOKENS", args.tokens.name))
    archive_paths = [archive_path for _, _, archive_path in resources]
    if len(archive_paths) != len(set(archive_paths)):
        raise SystemExit("Duplicate archive path; do not pass --tokens when the model directory already contains it")
    for path, _, _ in resources:
        if not path.is_file():
            raise SystemExit(f"Missing file: {path}")
    if args.output.suffix.lower() != ".zip":
        raise SystemExit("--output must end with .zip")

    manifest = {
        "schemaVersion": 1,
        "type": args.type,
        "displayName": args.name,
        "version": args.version,
        "cpuArchitecture": "arm64-v8a",
        "estimatedMemoryMb": args.memory_mb,
        "runtime": args.runtime,
        "license": args.license,
        "sampleRateHz": args.sample_rate,
        "files": [
            {
                "path": archive_path,
                "sha256": sha256(path),
                "required": True,
                "role": role,
            }
            for path, role, archive_path in resources
        ],
    }
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        archive.writestr("model_manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for path, _, archive_path in resources:
            archive.write(path, archive_path)
    print(args.output)


if __name__ == "__main__":
    main()
