import os

def bump_version(file_path):
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        return

    with open(file_path, 'r') as f:
        lines = f.readlines()

    new_lines = []
    found = False
    for line in lines:
        if 'extVersionCode =' in line:
            try:
                # Handle spaces around =
                parts = line.split('=')
                old_version = int(parts[1].strip())
                new_version = old_version + 1
                new_lines.append(f"    extVersionCode = {new_version}\n")
                print(f"Bumped {file_path}: {old_version} -> {new_version}")
                found = True
            except Exception as e:
                print(f"Error parsing line '{line}' in {file_path}: {e}")
                new_lines.append(line)
        else:
            new_lines.append(line)

    if found:
        with open(file_path, 'w') as f:
            f.writelines(new_lines)
    else:
        print(f"Could not find extVersionCode in {file_path}")

if __name__ == "__main__":
    files_to_bump = [
        "src/en/comix/build.gradle",
        "src/en/likemanga/build.gradle"
    ]
    
    for f in files_to_bump:
        bump_version(f)
