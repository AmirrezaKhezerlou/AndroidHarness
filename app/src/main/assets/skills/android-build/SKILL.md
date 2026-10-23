---
name: android-build
description: Build, install, or launch failed. Gradle, pm, then read the failure.
category: android
---

# Android build

Read the Gradle / pm error. Fix that error. Do not clean the universe first.

## When to use
- assembleDebug / install failed
- "could not find", SDK, manifest, R8, duplicate class

## When not to use
- The APK built and the app crashed (android-crash)

## Procedure
1. Find the wrapper (`search_files` `gradlew*`). Check: path.
2. For an on-device build, call `env_status`. Linux readiness covers the general coding shell, not the Android SDK. Check `java -version` and, for a manual DEX build, `d8 --version`. Use `pkg_search` / `pkg_install` for missing Java or D8 packages. D8's compiler is `$PREFIX/share/java/d8.jar`, never `/data/data/com.termux/...`. Keep native build tools in the exec-capable scratch directory reported by `env_status`.
3. `shell` `./gradlew :app:assembleDebug` (or the module that failed) from the project root. Check: the first real error, not the 200-line tail only.
4. `read_file` the cited file. Fix with `edit_file`.
5. Re-run the same Gradle task. Check: SUCCESS.
6. Install only if the user asked: `shell` `pm install -r <apk>` needs Shizuku / privileged tier. Shizuku is needed for privileged installation, not proof that the compiler/SDK is installed. If unavailable, offer opening the built APK with Android's installer or granting Shizuku. Do not send the user to a PC merely because a build prerequisite is missing; diagnose the actual error and install compatible on-device tools where available.

## Pitfalls
- `gradlew` needs the Linux prefix (or host JDK). If 126/127, `env_status` then stop.
- Do not `--offline` unless the error is clearly network.

## Verification
The same assemble command succeeds.
