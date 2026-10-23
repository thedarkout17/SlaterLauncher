# SlaterLauncher

A custom Android home screen for a 2000×1200 car head unit: blood-moon synthwave wallpaper, frosted-glass clock and music panels, and five app tiles.

## Folders

| Folder | What's in it |
| --- | --- |
| `app/` | The Android app (Java, no Gradle). `HomeView.java` draws the whole screen. |
| `preview/` | `index.html` is a clickable browser copy of the same layout, for trying changes before building. |
| `build.sh` | Builds and signs `dist/SlaterLauncher.apk`. |
| `.github/workflows/build.yml` | Builds the APK on every push to `main` and posts it on the Releases page. |

Layout numbers (panel positions, tile centres, colours) are on a 2000×1200 grid and are kept identical in `app/src/.../HomeView.java` and `preview/index.html`. Change both together.

## Getting the APK

Open the **Releases** page of this repo and download `SlaterLauncher.apk` from the newest build.

## Signing key

Android only installs an update over the existing app if both are signed with the same key. The build reads it from two repository secrets (Settings → Secrets and variables → Actions):

- `KEYSTORE_BASE64`: the keystore file, base64-encoded
- `KEYSTORE_PASSWORD`: its password

Without them the build still works, but uses a throwaway key, so you'd have to uninstall before installing that APK.

## Using it on the head unit

- Long-press any tile or sidebar icon to choose which app it opens.
- Long-press the background for wallpaper, music info access, and **Choose a different home app** (to return to the original UI33 launcher).
- To remove it: Settings → Apps → Redline Launcher → Uninstall. The original launcher comes back on its own.
