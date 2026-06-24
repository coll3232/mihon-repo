import os
import shutil
import hashlib
import json
import re

def get_sha256(filepath):
    sha256 = hashlib.sha256()
    with open(filepath, 'rb') as f:
        while chunk := f.read(8192):
            sha256.update(chunk)
    return sha256.hexdigest()

def main():
    apk_dir = "ext/build/outputs/apk/release"
    if not os.path.exists(apk_dir):
        # Fallback to check debug if release doesn't exist
        apk_dir = "ext/build/outputs/apk/debug"
        if not os.path.exists(apk_dir):
            print("Error: APK output directory not found.")
            return

    # Find APK file
    apk_files = [f for f in os.listdir(apk_dir) if f.endswith(".apk")]
    if not apk_files:
        print("Error: No APK file found in build outputs.")
        return

    # Use the first APK found
    source_apk_name = apk_files[0]
    source_apk_path = os.path.join(apk_dir, source_apk_name)
    
    # Read version details from ext/build.gradle
    version_name = "1.4.15"
    version_code = 15
    try:
        with open("ext/build.gradle", "r", encoding="utf-8") as f:
            gradle_content = f.read()
            vname_match = re.search(r'versionName\s*["\']([^"\']+)["\']', gradle_content)
            vcode_match = re.search(r'versionCode\s*(\d+)', gradle_content)
            if vname_match:
                version_name = vname_match.group(1)
            if vcode_match:
                version_code = int(vcode_match.group(1))
    except Exception as e:
        print(f"Warning: Could not read version details from build.gradle, using defaults: {e}")

    # Output directory for the repository
    repo_dir = "repo"
    os.makedirs(repo_dir, exist_ok=True)

    # Standardized output name for the APK
    pkg_name = "eu.kanade.tachiyomi.extension.ko.newtokitoki25"
    dest_apk_name = f"{pkg_name}-v{version_name}.apk"
    dest_apk_path = os.path.join(repo_dir, dest_apk_name)

    print(f"Copying APK to {dest_apk_path}...")
    shutil.copy2(source_apk_path, dest_apk_path)

    # Copy Icon
    icon_dir = os.path.join(repo_dir, "icon")
    os.makedirs(icon_dir, exist_ok=True)
    source_icon_path = "ext/src/main/res/drawable/icon.png"
    dest_icon_path = os.path.join(icon_dir, f"{pkg_name}.png")
    if os.path.exists(source_icon_path):
        print(f"Copying Icon to {dest_icon_path}...")
        shutil.copy2(source_icon_path, dest_icon_path)
    else:
        print("Warning: Icon file not found.")

    # Compute SHA-256
    apk_url = f"https://coll3232.github.io/kAsdmjkaw/{dest_apk_name}"

    # Generate repository index
    index_data = [
        {
            "name": "toon Ki",
            "pkg": pkg_name,
            "apk": apk_url,
            "lang": "ko",
            "code": version_code,
            "version": version_name,
            "nsfw": 1,
            "sources": [
                {
                    "name": "toon Ki",
                    "id": "1",
                    "lang": "ko",
                    "baseUrl": "https://toki25.com"
                }
            ]
        }
    ]

    index_path = os.path.join(repo_dir, "index.min.json")
    print(f"Generating index file at {index_path}...")
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index_data, f, ensure_ascii=False, indent=2)

    # Generate repo.json metadata for Mihon repository compatibility
    repo_meta = {
        "meta": {
            "name": "toon Ki Repository",
            "website": "https://github.com/coll3232/kAsdmjkaw"
        }
    }
    repo_path = os.path.join(repo_dir, "repo.json")
    print(f"Generating repo.json file at {repo_path}...")
    with open(repo_path, "w", encoding="utf-8") as f:
        json.dump(repo_meta, f, ensure_ascii=False, indent=2)

    print("Repository build successfully completed!")

if __name__ == "__main__":
    main()
