import re
import os
import sys

def bump_version(file_path):
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        return

    with open(file_path, 'r') as f:
        content = f.read()

    # Find extVersionCode = X
    pattern = r"(extVersionCode\s*=\s*)(\d+)"
    match = re.search(pattern, content)
    
    if match:
        old_version = int(match.group(2))
        new_version = old_version + 1
        # Use \1 instead of \\1 to avoid potential escaping issues in some environments
        # Or even better, just construct the string
        new_content = re.sub(pattern, rf"\g<1>{new_version}", content)
        
        with open(file_path, 'w') as f:
            f.write(new_content)
        print(f"Bumped {file_path}: {old_version} -> {new_version}")
    else:
        print(f"Could not find extVersionCode in {file_path}")

if __name__ == "__main__":
    # List of build.gradle files to bump
    files_to_bump = [
        "src/en/comix/build.gradle",
        "src/en/likemanga/build.gradle"
    ]
    
    for f in files_to_bump:
        bump_version(f)