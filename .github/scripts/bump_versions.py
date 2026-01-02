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
        if 'extVersionCode' in line and '=' in line:
            try:
                # Be extremely specific with the split to avoid replacing the whole line with junk
                prefix, version_part = line.split('=', 1)
                # Extract the number from the part after =
                current_version = "".join(filter(str.isdigit, version_part))
                if current_version:
                    new_version = int(current_version) + 1
                    # Reconstruct the line carefully keeping indentation
                    new_lines.append(f"{prefix}= {new_version}\n")
                    print(f"Bumped {file_path}: {current_version} -> {new_version}")
                    found = True
                else:
                    new_lines.append(line)
            except Exception as e:
                print(f"Error parsing line '{line}' in {file_path}: {e}")
                new_lines.append(line)
        else:
            new_lines.append(line)

    if found:
        with open(file_path, 'w') as f:
            f.writelines(new_lines)
    else:
        print(f"Could not find valid extVersionCode line in {file_path}")

if __name__ == "__main__":
    files_to_bump = [
        "src/all/mangafire/build.gradle"
    ]
    
    for f in files_to_bump:
        bump_version(f)