# Releases

Built APKs go here. Everything else in the repo ignores `*.apk` (see the root
`.gitignore`) since build outputs are normally regenerated from source, not versioned —
this folder is a deliberate, narrow exception so a specific build can be committed for
someone to grab directly from the repo.

## Building an APK

This needs a JDK and the Android SDK (neither is available in the sandbox this was
scaffolded from), so build locally or via CI:

```
cd android-app
./gradlew assembleDebug     # unsigned debug build
# output: android-app/app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease   # release build (needs signing config)
# output: android-app/app/build/outputs/apk/release/app-release.apk
```

Then copy the resulting `.apk` into this folder and commit it, e.g.:

```
cp android-app/app/build/outputs/apk/debug/app-debug.apk releases/
git add releases/app-debug.apk
git commit -m "Add debug APK build"
```

## Naming

Prefer a version/date in the filename (e.g. `healthdecoder-2026-07-14-debug.apk`) so old
builds aren't silently overwritten when a new one is added.
