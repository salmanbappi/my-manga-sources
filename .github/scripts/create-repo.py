import json
import os
import re
import subprocess
import hashlib
from pathlib import Path
from zipfile import ZipFile

def get_file_sha256(file_path):
    with open(file_path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.extension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")

# We are in GitHub Actions, aapt should be available or we find it
try:
    # Try finding aapt in ANDROID_HOME
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        build_tools = list((Path(android_home) / "build-tools").iterdir())
        if build_tools:
            # Use the latest build tools
            build_tools.sort()
            aapt_cmd = str(build_tools[-1] / "aapt")
        else:
            aapt_cmd = "aapt"
    else:
        aapt_cmd = "aapt"
except:
    aapt_cmd = "aapt"

REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

if not os.path.exists("output.json"):
    print("Error: output.json not found!")
    exit(1)

with open("output.json", encoding="utf-8") as f:
    inspector_data = json.load(f)

index_min_data = []

for apk in REPO_APK_DIR.iterdir():
    if not apk.name.endswith(".apk"):
        continue

    try:
        badging = subprocess.check_output(
            [
                aapt_cmd,
                "dump",
                "--include-meta-data",
                "badging",
                str(apk),
            ]
        ).decode()
    except Exception as e:
        print(f"Failed to dump badging for {apk.name}: {e}")
        continue

    package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
    package_name = PACKAGE_NAME_REGEX.search(package_info)[1]
    
    # Icon extraction
    try:
        application_icon_match = APPLICATION_ICON_320_REGEX.search(badging)
        if application_icon_match:
            application_icon = application_icon_match[1]
            with ZipFile(apk) as z, z.open(application_icon) as i, (
                REPO_ICON_DIR / f"{package_name}.png"
            ).open("wb") as f:
                f.write(i.read())
    except:
        pass

    language = LANGUAGE_REGEX.search(apk.name)[1]
    
    # Get sources from Inspector output
    sources = inspector_data.get(package_name, [])

    if len(sources) == 1:
        source_language = sources[0]["lang"]
        if (
            source_language != language
            and source_language not in {"all", "other"}
            and language not in {"all", "other"}
        ):
            language = source_language

    common_data = {
        "name": APPLICATION_LABEL_REGEX.search(badging)[1],
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": int(VERSION_CODE_REGEX.search(package_info)[1]),
        "version": VERSION_NAME_REGEX.search(package_info)[1],
        "nsfw": int(IS_NSFW_REGEX.search(badging)[1]),
        "size": os.path.getsize(apk),
        "sha256": get_file_sha256(apk),
        "icon": f"icon/{package_name}.png"
    }
    
    min_data = {
        **common_data,
        "sources": sources,
    }

    index_min_data.append(min_data)

# Save index.min.json (The actual list of extensions)
with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as index_file:
    json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))

# Save index.json (Pretty print version)
with REPO_DIR.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index_min_data, index_file, ensure_ascii=False, indent=2)

# Save repo.json (METADATA - This was the bug!)
repo_info = {
    "meta": {
        "name": "SalmanBappi Manga Repo",
        "shortName": "SBManga",
        "website": "https://salmanbappi.github.io/salmanbappi-manga-extension/",
        "signingKeyFingerprint": "" 
    }
}
with REPO_DIR.joinpath("repo.json").open("w", encoding="utf-8") as repo_file:
    json.dump(repo_info, repo_file, indent=2)