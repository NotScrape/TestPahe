#!/usr/bin/env python3
"""
Builds the `repo` branch contents from a folder of assembled APKs.

Output:
  repo/
    apk/
      *.apk
    icon/
      <package>.png
    index.json
    index.min.json

Usage:
    python create-repo.py <apk_input_dir> <repo_output_dir>

Requires:
    pip install androguard
"""

import hashlib
import json
import shutil
import sys
from pathlib import Path

try:
    from androguard.core.apk import APK
except ImportError:
    sys.exit(
        "androguard is required: pip install androguard --break-system-packages"
    )


# Aniyomi extension metadata
NSFW_META = "tachiyomi.animeextension.nsfw"
VERSION_ID_META = "tachiyomi.animeextension.versionId"
NAMES_META = "tachiyomi.animeextension.names"


def get_id(name: str, version_id: int) -> str:
    """
    Generates the source ID used by Aniyomi/Mihon-style extension repos.
    """
    key = f"{name.lower()}/all/{version_id}"
    md5_hash = hashlib.md5(key.encode()).digest()

    result = 0
    for i in range(8):
        result |= (md5_hash[i] & 0xFF) << (8 * (7 - i))

    return str(result & 0x7FFFFFFFFFFFFFFF)


def get_metadata(apk: APK) -> dict[str, str]:
    """
    Extract application meta-data from AndroidManifest.xml.
    """
    metadata = {}

    manifest = apk.get_android_manifest_xml()

    for tag in manifest.getElementsByTagName("meta-data"):
        name = tag.getAttribute("android:name")
        value = tag.getAttribute("android:value")

        if name:
            metadata[name] = value

    return metadata


def extract_icon(apk: APK, output_path: Path) -> None:
    """
    Extract the highest-resolution ic_launcher.png available.
    """
    candidates = [
        file
        for file in apk.get_files()
        if file.endswith("ic_launcher.png")
    ]

    if not candidates:
        print(f"[create-repo] warning: no ic_launcher.png found")
        return

    # Prefer higher-density icons.
    priority = {
        "xxxhdpi": 0,
        "xxhdpi": 1,
        "xhdpi": 2,
        "hdpi": 3,
        "mdpi": 4,
    }

    candidates.sort(
        key=lambda file: (
            min(
                (
                    priority[density]
                    for density in priority
                    if density in file
                ),
                default=99,
            ),
            file,
        )
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(apk.get_file(candidates[0]))


def get_language(apk_path: Path) -> str:
    """
    Gets the extension language from the APK filename.

    Expected examples:
        aniyomi-en.extension.apk
        aniyomi-all.extension.apk
    """
    parts = apk_path.name.split("-")

    if len(parts) >= 2 and parts[0].lower() == "aniyomi":
        return parts[1].split(".")[0]

    return "all"


def build_entry(apk_path: Path, icon_dir: Path) -> dict | None:
    """
    Parse one APK and create its repository entry.
    """
    apk = APK(str(apk_path))

    package_name = apk.get_package()

    metadata = get_metadata(apk)

    # ---------------------------------------------------------
    # Basic APK information
    # ---------------------------------------------------------

    version_name = apk.get_androidversion_name()
    version_code = int(apk.get_androidversion_code())
    application_label = apk.get_app_name() or package_name

    # ---------------------------------------------------------
    # Aniyomi metadata
    # ---------------------------------------------------------

    nsfw = 1 if metadata.get(NSFW_META) == "true" else 0

    version_id_raw = metadata.get(VERSION_ID_META)

    if version_id_raw:
        version_id = int(version_id_raw)
    else:
        # Fallback in case the APK doesn't contain versionId.
        version_id = version_code

    names_raw = metadata.get(NAMES_META)

    if names_raw:
        names = [
            name.strip()
            for name in names_raw.split(";")
            if name.strip()
        ]
    else:
        # Fallback to application label.
        names = [application_label]

    # ---------------------------------------------------------
    # Language
    # ---------------------------------------------------------

    language = get_language(apk_path)

    # ---------------------------------------------------------
    # Icon
    # ---------------------------------------------------------

    icon_dir.mkdir(parents=True, exist_ok=True)
    extract_icon(
        apk,
        icon_dir / f"{package_name}.png",
    )

    # ---------------------------------------------------------
    # Sources
    # ---------------------------------------------------------

    sources = []

    for name in names:
        sources.append(
            {
                "name": name,
                "lang": language,
                "id": get_id(name, version_id),
                "baseUrl": "",
                "versionId": version_id,
            }
        )

    # ---------------------------------------------------------
    # Final repository entry
    # ---------------------------------------------------------

    return {
        "name": application_label,
        "pkg": package_name,
        "apk": apk_path.name,
        "lang": language,
        "code": version_code,
        "version": version_name,
        "nsfw": nsfw,
        "hasReadme": 0,
        "hasChangelog": 0,
        "sources": sources,
    }


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)

    apk_input_dir = Path(sys.argv[1])
    repo_dir = Path(sys.argv[2])

    apk_output_dir = repo_dir / "apk"
    icon_output_dir = repo_dir / "icon"

    if not apk_input_dir.exists():
        sys.exit(
            f"[create-repo] APK input directory does not exist: "
            f"{apk_input_dir}"
        )

    apk_output_dir.mkdir(parents=True, exist_ok=True)
    icon_output_dir.mkdir(parents=True, exist_ok=True)

    entries = []

    # ---------------------------------------------------------
    # Process APKs
    # ---------------------------------------------------------

    for apk_path in sorted(apk_input_dir.glob("*.apk")):
        try:
            entry = build_entry(
                apk_path,
                icon_output_dir,
            )

            if entry is None:
                continue

            # Copy APK into repo/apk/
            shutil.copy2(
                apk_path,
                apk_output_dir / apk_path.name,
            )

            entries.append(entry)

            print(
                f"[create-repo] processed {apk_path.name}"
            )

        except Exception as exc:
            print(
                f"[create-repo] skipping {apk_path.name}: {exc}",
                file=sys.stderr,
            )

    # ---------------------------------------------------------
    # Sort entries
    # ---------------------------------------------------------

    entries.sort(key=lambda entry: entry["pkg"])

    # ---------------------------------------------------------
    # index.json
    # ---------------------------------------------------------

    index_json = repo_dir / "index.json"

    index_json.write_text(
        json.dumps(
            entries,
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    # ---------------------------------------------------------
    # index.min.json
    # ---------------------------------------------------------

    index_min_json = repo_dir / "index.min.json"

    index_min_json.write_text(
        json.dumps(
            entries,
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )

    print(
        f"[create-repo] wrote {len(entries)} extension entries"
    )


if __name__ == "__main__":
    main()
