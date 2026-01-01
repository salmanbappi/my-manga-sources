import html
import sys
import json
from pathlib import Path
import shutil

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath(sys.argv[2])

to_delete: list[str] = json.loads(sys.argv[1])

# Ensure apk and icon directories exist in remote repo
REMOTE_REPO.joinpath("apk").mkdir(exist_ok=True)
REMOTE_REPO.joinpath("icon").mkdir(exist_ok=True)

for module in to_delete:
    apk_name = f"tachiyomi-{module}-v*.*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.extension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(f"Deleting {file.name}")
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(f"Deleting {file.name}")
        file.unlink(missing_ok=True)

shutil.copytree(src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok = True)
shutil.copytree(src=LOCAL_REPO.joinpath("icon"), dst=REMOTE_REPO.joinpath("icon"), dirs_exist_ok = True)

# Handle missing index.json (first run case)
index_file_path = REMOTE_REPO.joinpath("index.json")
if index_file_path.exists():
    with index_file_path.open() as remote_index_file:
        remote_index = json.load(remote_index_file)
else:
    remote_index = []

with LOCAL_REPO.joinpath("index.min.json").open() as local_index_file:
    local_index = json.load(local_index_file)

# Filter out deleted modules from remote index
remote_index = [
    item
    for item in remote_index
    if not any(item["pkg"].endswith(f".{module}") for module in to_delete)
]

# Explicitly remove "Example Extension" if present (since we deleted source without triggering delete logic)
remote_index = [
    item for item in remote_index 
    if "example" not in item["pkg"]
]

# Merge logic: Create a dict by pkg name to override old with new
merged_map = {item['pkg']: item for item in remote_index}
for item in local_index:
    merged_map[item['pkg']] = item

# Convert back to list
index = list(merged_map.values())
index.sort(key=lambda x: x["pkg"])

# FIX PATHS FOR ALL ITEMS
for item in index:
    # Fix APK path
    if "apk" in item:
        apk_name = item["apk"].split("/")[-1] # Get just the filename
        item["apk"] = f"apk/{apk_name}" # Force relative apk/ prefix
    
    # Fix Icon path
    if "icon" in item:
        # If it's a full URL, leave it (or convert? Let's leave absolute if it works)
        # If it's relative, ensure it starts with icon/
        if not item["icon"].startswith("http"):
             icon_name = item["icon"].split("/")[-1]
             item["icon"] = f"icon/{icon_name}"

    # Cleanup sources versionId
    if "sources" in item:
        for source in item["sources"]:
            source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=( ",", ":"))

with REMOTE_REPO.joinpath("repo.json").open("w", encoding="utf-8") as repo_file:
    json.dump(index, repo_file, ensure_ascii=False, separators=( ",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"].split("/")[-1])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')
