#!/usr/bin/env python3
"""
Builds the `repo` branch contents from a folder of assembled APKs:
  - copies/renames APKs into apk/
  - extracts each extension's icon into icon/<pkg>.png
  - writes index.json and index.min.json describing every extension

This mirrors the metadata format Mihon/Aniyomi-based apps expect from a
third-party extension repo (the same shape Keiyoushi's `extensions` repo
and Secozzi's `aniyomi-extensions` repo branch publish).

Usage:
    python create-repo.py <apk_input_dir> <repo_output_dir>

Requires: androguard (pip install androguard --break-system-packages)
"""
import json
import shutil
import sys
import zipfile
from pathlib import Path

try:
    from androguard.core.apk import APK
except ImportError:
    sys.exit(
        "androguard is required: pip install androguard --break-system-packages"
    )

NSFW_META = "tachiyomi.animeextension.nsfw"
CLASS_META = "tachiyomi.animeextension.class"
# Swap the two constants above for the manga equivalents if this repo also
# ships Tachiyomi/manga sources instead of (or alongside) anime sources:
#   tachiyomi.extension.nsfw / tachiyomi.extension.class


def extract_icon(apk: APK, out_path: Path) -> None:
    # Extensions ship a single mipmap icon named ic_launcher.png (all densities
    # point at the same drawable in these build systems); grab the highest-res one.
    candidates = sorted(
        (f for f in apk.get_files() if f.endswith("ic_launcher.png")),
        key=lambda f: ("xxxhdpi" not in f, "xxhdpi" not in f, "xhdpi" not in f, f),
    )
    if not candidates:
        return
    data = apk.get_file(candidates[0])
    out_path.write_bytes(data)


def build_entry(apk_path: Path, icon_dir: Path) -> dict | None:
    apk = APK(str(apk_path))
    pkg = apk.get_package()
    meta = apk.get_all_attribute_value(
        "application", "value", namespace=None
    )  # not used directly; kept for readability

    app_meta = {}
    for tag in apk.get_android_manifest_xml().getElementsByTagName("meta-data"):
        name = tag.getAttribute("android:name")
        value = tag.getAttribute("android:value")
        app_meta[name] = value

    class_name = app_meta.get(CLASS_META, "")
    nsfw = 1 if app_meta.get(NSFW_META) == "true" else 0

    version_name = apk.get_androidversion_name()
    version_code = int(apk.get_androidversion_code())
    label = apk.get_app_name() or pkg

    icon_dir.mkdir(parents=True, exist_ok=True)
    extract_icon(apk, icon_dir / f"{pkg}.png")

    lang = pkg.split(".")[-2] if pkg.count(".") >= 2 else "all"

    return {
        "name": label,
        "pkg": pkg,
        "apk": apk_path.name,
        "lang": lang,
        "code": version_code,
        "version": version_name,
        "nsfw": nsfw,
        "hasReadme": False,
        "hasChangelog": False,
        "sources": [
            {
                "name": label,
                "id": 0,
                "baseUrl": "",
                "lang": lang,
                "versionId": version_code,
            }
        ],
        "className": class_name,
    }


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)

    apk_dir = Path(sys.argv[1])
    repo_dir = Path(sys.argv[2])
    apk_out = repo_dir / "apk"
    icon_out = repo_dir / "icon"
    apk_out.mkdir(parents=True, exist_ok=True)

    entries = []
    for apk_path in sorted(apk_dir.glob("*.apk")):
        try:
            entry = build_entry(apk_path, icon_out)
        except Exception as exc:  # noqa: BLE001
            print(f"[create-repo] skipping {apk_path.name}: {exc}", file=sys.stderr)
            continue
        if entry is None:
            continue
        shutil.copy2(apk_path, apk_out / apk_path.name)
        entries.append(entry)

    entries.sort(key=lambda e: (e["lang"], e["name"]))

    (repo_dir / "index.json").write_text(json.dumps(entries, indent=2))
    (repo_dir / "index.min.json").write_text(json.dumps(entries, separators=(",", ":")))
    print(f"[create-repo] wrote {len(entries)} extension entries")


if __name__ == "__main__":
    main()
