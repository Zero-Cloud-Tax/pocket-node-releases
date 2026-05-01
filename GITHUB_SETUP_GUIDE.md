# Pocket Node GitHub Distribution Guide

To allow users to download your app without Android Studio, we have set up GitHub Actions to automatically build the APK and publish it to GitHub Releases, as well as deploy your landing page to GitHub Pages. 

Follow these steps to configure your GitHub repository to make this work.

## 1. Push Code to GitHub
If you haven't already, push your entire `Pocket Node` project to a GitHub repository.

## 2. Generate a Release Keystore (If you don't have one)
To sign your APK for release, you need a keystore file. If you haven't generated one yet, you can run the included script:
```bash
./keygen.sh
```
This will create a `release.keystore` file and a `keystore.properties` file locally. **Do NOT commit `release.keystore` or `keystore.properties` to GitHub for security reasons.** (They should already be in your `.gitignore`).

## 3. Set Up GitHub Secrets
The GitHub Action needs your keystore to build the release APK. Since you can't upload the keystore directly to the repo, you'll store it in GitHub Secrets.

1. Go to your GitHub Repository -> **Settings** -> **Secrets and variables** -> **Actions**.
2. Click **New repository secret**.
3. Add the following 4 secrets:

| Secret Name | Value |
|-------------|-------|
| `KEYSTORE_PASSWORD` | The password for your keystore (e.g., from `keystore.properties`) |
| `KEY_PASSWORD` | The password for your key alias (usually the same as above) |
| `KEY_ALIAS` | The alias of your key (e.g., `pocketnode`) |
| `KEYSTORE_BASE64` | *See below* |

**To get the `KEYSTORE_BASE64` value:**
You need to convert your `release.keystore` file to a base64 string.
- **On Windows (PowerShell):**
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | clip
  ```
  *(This copies the base64 string directly to your clipboard. Paste it into the Secret value box).*

## 4. Trigger an APK Build
To build and release an APK, simply create a tag that starts with `v` and push it to GitHub:
```bash
git tag v1.0.0
git push origin v1.0.0
```
- Go to the **Actions** tab in your repository to watch the build process.
- Once complete, it will automatically create a new Release on your GitHub page with the `PocketNodeLite.apk` attached!

## 5. Enable GitHub Pages for the Landing Site
The landing page will automatically deploy when you push to `main` (or `master`), but you need to enable it in GitHub settings:
1. Go to **Settings** -> **Pages**.
2. Under **Build and deployment**, set the **Source** to **GitHub Actions**.
3. Push any change to your `main` branch. 
4. Your landing page will now be live at `https://[your-username].github.io/[repo-name]/`!

## 6. Updating the Download Links
Once your GitHub Release and GitHub Pages are live, you can update the `href` in your `landing/index.html` to point directly to your latest GitHub release, e.g.:
```html
<a href="https://github.com/your-username/Pocket-Node/releases/latest/download/PocketNodeLite.apk" ...>Download Free APK</a>
```
