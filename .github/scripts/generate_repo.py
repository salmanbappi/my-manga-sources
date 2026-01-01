import json
import os
import hashlib
import re
from zipfile import ZipFile

def get_apk_size(file_path):
    return os.path.getsize(file_path)

def get_file_sha256(file_path):
    with open(file_path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()

def generate():
    repo_data = {}
    
    # Path relative to where script is run (which is root of source repo)
    base_dir = "repo"
    apk_dir = os.path.join(base_dir, "apk")
    icon_dir = os.path.join(base_dir, "icon")
    
    # Ensure directories exist
    if not os.path.exists(apk_dir):
        os.makedirs(apk_dir)
    if not os.path.exists(icon_dir):
        os.makedirs(icon_dir)

    print(f"Scanning {apk_dir}...")

    # Process all APKs in the apk directory
    for apk_name in os.listdir(apk_dir):
        if not apk_name.endswith(".apk"):
            continue
            
        apk_path = os.path.join(apk_dir, apk_name)
        print(f"Processing {apk_name}...")
        
        try:
            # Defaults
            pkg = apk_name.replace(".apk", "")
            code = 1
            version = "1.0"
            name = pkg
            lang = "en"
            
            try:
                # Find aapt
                from subprocess import check_output
                from pathlib import Path
                
                android_home = os.environ.get("ANDROID_HOME")
                if android_home:
                    build_tools = list((Path(android_home) / "build-tools").iterdir())
                    if build_tools:
                        aapt_cmd = str(build_tools[-1] / "aapt")
                    else:
                        aapt_cmd = "aapt"
                else:
                    aapt_cmd = "aapt"

                badging = check_output([aapt_cmd, "dump", "badging", apk_path]).decode()
                
                # Extract real metadata
                pkg_match = re.search(r"package: name='([^']+)'", badging)
                ver_code_match = re.search(r"versionCode='([^']+)'", badging)
                ver_name_match = re.search(r"versionName='([^']+)'", badging)
                label_match = re.search(r"application-label:'([^']+)'", badging)
                icon_match = re.search(r"application-icon-320:'([^']+)'", badging)
                
                if pkg_match: pkg = pkg_match.group(1)
                if ver_code_match: code = int(ver_code_match.group(1))
                if ver_name_match: version = ver_name_match.group(1)
                if label_match: name = label_match.group(1)
                
                # Extract Icon
                if icon_match:
                    icon_path_in_apk = icon_match.group(1)
                    try:
                        with ZipFile(apk_path) as z:
                            with z.open(icon_path_in_apk) as i_file:
                                with open(f"{icon_dir}/{pkg}.png", "wb") as f:
                                    f.write(i_file.read())
                    except Exception as icon_e:
                        print(f"Failed to extract icon: {icon_e}")

                
                # Language logic
                if "tachiyomi-" in apk_name:
                     match = re.search(r"tachiyomi-([^.]+)", apk_name)
                     if match: lang = match.group(1)
                
            except Exception as e:
                print(f"Warning: aapt failed for {apk_name}: {e}")

            item = {
                "name": name,
                "pkg": pkg,
                "apk": apk_name,
                "lang": lang,
                "code": code,
                "version": version,
                "nsfw": 1, 
                "hasReadme": 0,
                "hasChangelog": 0,
                "icon": f"icon/{pkg}.png"
            }
            item["size"] = get_apk_size(apk_path)
            item["sha256"] = get_file_sha256(apk_path)
            
            repo_data[pkg] = item
            
        except Exception as e:
            print(f"Skipping {apk_name} due to error: {e}")

    # Convert dict to sorted list
    final_data = sorted(repo_data.values(), key=lambda x: x["name"])

    # Save index.min.json in repo/
    with open(os.path.join(base_dir, "index.min.json"), "w") as f:
        json.dump(final_data, f, separators=(',', ':'))

    # Save index.json in repo/
    with open(os.path.join(base_dir, "index.json"), "w") as f:
        json.dump(final_data, f, indent=2)
        
    # Save repo.json (Metadata for repo listing) in repo/
    repo_info = {
        "meta": {
            "name": "SalmanBappi Manga Repo",
            "shortName": "SBManga",
            "website": "https://salmanbappi.github.io/salmanbappi-manga-extension/",
            "signingKeyFingerprint": "" # Optional
        }
    }
    with open(os.path.join(base_dir, "repo.json"), "w") as f:
        json.dump(repo_info, f, indent=2)

if __name__ == "__main__":
    generate()