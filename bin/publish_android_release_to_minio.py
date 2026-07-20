#!/usr/bin/env python3
import argparse
import json
import os
import sys
from typing import Any


REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from music_minio import publish_android_release  # noqa: E402


def read_release_metadata(metadata_path: str | None) -> dict[str, Any]:
    if not metadata_path or not os.path.exists(metadata_path):
        return {}
    with open(metadata_path, "r", encoding="utf-8") as f:
        metadata = json.load(f)
    element = ((metadata.get("elements") or [{}])[:1] or [{}])[0]
    return {
        "versionCode": element.get("versionCode"),
        "versionName": element.get("versionName"),
        "fileName": element.get("outputFile"),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish signed Android release APK to MinIO.")
    parser.add_argument("--apk", required=True, help="Path to the signed release APK.")
    parser.add_argument("--metadata", default="", help="Path to Gradle output-metadata.json.")
    parser.add_argument("--version-code", type=int, default=None, help="Override versionCode.")
    parser.add_argument("--version-name", default="", help="Override versionName.")
    parser.add_argument("--file-name", default="", help="Override published file name.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    apk_path = os.path.abspath(args.apk)
    if not os.path.exists(apk_path):
        raise FileNotFoundError(f"APK not found: {apk_path}")

    metadata = read_release_metadata(args.metadata)
    version_code = args.version_code
    if version_code is None and metadata.get("versionCode") is not None:
        version_code = int(metadata["versionCode"])
    version_name = args.version_name.strip() or metadata.get("versionName")
    file_name = args.file_name.strip() or metadata.get("fileName") or os.path.basename(apk_path)

    manifest = publish_android_release(
        apk_path=apk_path,
        version_code=version_code,
        version_name=version_name,
        file_name=file_name,
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
