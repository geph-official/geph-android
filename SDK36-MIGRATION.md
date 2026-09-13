# Android 16 / API 36 migration

The app compiles against and targets API 36, with AGP 8.10.1 and Gradle
8.11.1. The minimum SDK remains 21; Kotlin, Java bytecode level, and NDK
versions remain unchanged. API 37 is deferred to keep this upgrade smaller.

## Compatibility changes

- On Android 15+, the activity explicitly pads the WebView container for
  system bars, display cutouts, and the keyboard. Padding uses the maximum
  inset on each edge, rather than adding keyboard and navigation-bar heights.
  The obsolete edge-to-edge opt-out theme is removed. Older devices retain
  their existing `fitsSystemWindows` layout.
- The API 36 large-screen compatibility property preserves the existing
  portrait restriction. Before targeting API 37, remove that property and
  validate activity recreation and WebView state during rotation and resizing.
- Back navigation already uses AndroidX `OnBackPressedDispatcher`, so it
  does not depend on the legacy `Activity.onBackPressed` or back-key events.
- The VPN retains its `specialUse` foreground service declaration. No new
  LAN permission is needed for target 36.
- Android's `Os.dup2` replaces the JNA call used to pass the VPN descriptor
  to the engine on API 26+. It clears close-on-exec on the duplicated stdin
  descriptor, while the original descriptor remains owned by
  `ParcelFileDescriptor`. This removes the old JNA native library and its
  known 16 KB page-size runtime problem. The API 21–25 stdio path is unchanged.
- Lint also exposed an existing API 21–25 crash in daemon shutdown:
  `Process.destroyForcibly` requires API 26. Older devices now use `destroy`,
  and inherited-descriptor setup has an explicit API 26 guard.

## Build validation (2026-09-13)

- Full `assembleDebug`, `assembleReleaseAPK`, and `bundleReleasePlay` passed,
  including the web UI build and both Rust Android targets from `PREBUILD.sh`.
- Debug, release APK, and Play lint completed with zero errors (28, 26, and
  30 warnings, respectively, covering dependency updates, style, and resources).
- Both APK manifests report min SDK 21 and target SDK 36. Native library
  extraction is enabled, as required to launch `libgeph.so` as a subprocess.
- Both APKs passed `zipalign -c -P 16 4`. All three artifacts contain the
  web UI, shared engine configuration, and both supported engine ABIs; JNA
  is absent. The arm64 engine's ELF LOAD segments are 16 KB aligned.
- No runtime tests were run: no device was connected and `/dev/kvm` was
  unavailable on the build host.

## Device validation before release

Build and static checks cannot establish runtime compatibility. Test both
the APK and Play distribution, including an upgrade over an existing install:

- API 21–25: startup, login, connect, traffic, disconnect (legacy stdio path).
- API 26–35: connect, change exit/settings while connected, reconnect, and
  stop from the notification (inherited VPN descriptor path).
- API 35 and 36: gesture and three-button navigation; keyboard show/hide;
  login and settings fields; cutouts; light/dark themes; WebView back navigation.
- API 36 on a 16 KB device: native engine startup, IPv4/IPv6 and DNS traffic,
  screen-off/background operation, Wi-Fi/mobile handover, proxy sharing, and
  app exclusions. Check for native crashes and descriptor errors in logcat.
- Tablet/foldable: portrait compatibility mode, split screen, resume, and
  preservation of the active VPN across activity recreation.
- APK updater/install intent and log export; Play review and analytics paths.

## References

- [Android 16 target behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)
- [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [Edge-to-edge views](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [JNA 16 KB runtime issue](https://github.com/java-native-access/jna/issues/1647)
- [Android Os API](https://developer.android.com/reference/android/system/Os)
