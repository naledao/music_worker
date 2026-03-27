#!/usr/bin/env python3

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
MANAGED_DIR = REPO_ROOT / "run" / "app-packages" / "desktop"
MANIFEST_PATH = MANAGED_DIR / "manifest.json"


def iso_ts(ts: float | None = None) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts or time.time()))


def sha256_file(file_path: Path) -> str:
    digest = hashlib.sha256()
    with file_path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def infer_version_name(file_name: str) -> str | None:
    match = re.search(r"-(\d+(?:\.\d+)+(?:[-+._A-Za-z0-9]*)?)\.(?:exe|msi)$", file_name, flags=re.IGNORECASE)
    if match:
        version_name = match.group(1).strip()
        if version_name:
            return version_name
    return None


def find_installers(source: Path) -> dict[str, Path]:
    if not source.exists():
        raise FileNotFoundError(f"source path not found: {source}")

    if source.is_file():
        candidates = [source]
    else:
        candidates = [path for path in source.rglob("*") if path.is_file() and path.suffix.lower() in (".exe", ".msi")]

    packages: dict[str, Path] = {}
    for path in sorted(candidates, key=lambda item: item.stat().st_mtime, reverse=True):
        kind = path.suffix.lower().lstrip(".")
        packages.setdefault(kind, path)

    if not packages:
        raise FileNotFoundError(f"no desktop installers found under: {source}")
    return packages


def clear_existing_installers() -> None:
    if not MANAGED_DIR.exists():
        return
    for pattern in ("*.exe", "*.msi", "manifest.json"):
        for path in MANAGED_DIR.glob(pattern):
            if path.is_file():
                path.unlink()


def publish_installers(source: Path, version_name: str | None = None) -> dict[str, object]:
    packages = find_installers(source)
    MANAGED_DIR.mkdir(parents=True, exist_ok=True)
    clear_existing_installers()

    manifest_packages: dict[str, dict[str, object]] = {}
    inferred_version = version_name

    for kind, package_path in sorted(packages.items()):
        target_path = MANAGED_DIR / package_path.name
        shutil.copy2(package_path, target_path)

        package_version = version_name or infer_version_name(package_path.name)
        if not inferred_version and package_version:
            inferred_version = package_version

        manifest_packages[kind] = {
            "fileName": target_path.name,
            "fileSize": target_path.stat().st_size,
            "sha256": sha256_file(target_path),
            "versionName": package_version,
            "publishedAt": iso_ts(target_path.stat().st_mtime),
        }

    manifest = {
        "versionName": inferred_version,
        "publishedAt": iso_ts(),
        "sourcePath": str(source.resolve()),
        "packages": manifest_packages,
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish desktop EXE/MSI installers into backend-managed directory.")
    parser.add_argument("--source", required=True, help="Installer file or directory. Supports extracted GitHub Actions artifact directory.")
    parser.add_argument("--version", default=None, help="Optional override for versionName written into manifest.")
    args = parser.parse_args()

    source = Path(args.source).expanduser().resolve()
    manifest = publish_installers(source=source, version_name=args.version)

    print(f"Managed directory: {MANAGED_DIR}")
    print(f"Manifest: {MANIFEST_PATH}")
    for kind, package_info in sorted((manifest.get('packages') or {}).items()):
        if isinstance(package_info, dict):
            print(f"{kind.upper()}: {package_info.get('fileName')}")
            print(f"  download: /api/app/package?platform=desktop&kind={kind}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
