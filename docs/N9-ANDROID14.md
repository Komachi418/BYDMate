# N9 / Android 14 read-only test

This branch belongs to `Komachi418/BYDMate`. It is a diagnostic test build, not a
production vehicle-control release. No vehicle write/control tests are permitted.

## Install and use

- Download the `BYDMate-N9-readonly-<commit>` artifact from this fork's
  **N9 Android 14 read-only APK** Actions run, unzip it and install its APK.
- The package is **com.bydmate.app.n9test**, labelled **BYDMate N9 Test**.
  Keep the official `com.bydmate.app` installed. Never uninstall it or clear its
  data to install this test. Its existing WRITE_SECURE_SETTINGS grant is untouched.
- Run only while parked. Keep the official app responsible for its existing ADB
  recovery. This package does not enable wireless debugging, restore port 5555,
  grant permissions, pair TLS ADB, or issue vehicle controls.
- Open the test app and use the explicit read/diagnostic buttons. A separate ADB
  authorization prompt may appear because this package owns a separate key.
- Save or copy the diagnostic report. Check Android userId, classic 5555 connection,
  TLS observation, autoservice probe/raw Parcel, SOC/SoH, cell readings and Helper
  transport. Unsupported/unavailable values must remain unavailable, not zero.
- Helper testing is optional and separate from the direct autoservice reads. A
  failed Helper must not hide successful battery reads. Do not change SELinux,
  root the head unit, or use permissive mode to make it work.

## Evidence and root causes

The user's complete original `bydmate_logs_20261007_185755.txt` was transferred
from the referenced chat without rewriting and checked locally:

- 24,211 bytes
- SHA-256 `bbe03154e7267ad9b60e41d4a34e6c215344c270d4de6adfb0fa443fcfc5cfd4`
- Android 14 / SDK 34, BYD AUTO Di300VCP, BYDMate 3.19.9 (503).
- Lines 427-429 and 447-449 contain literal tabs after `Parcel(`. The old parser
  expected `Parcel(00000000` and rejected valid scalar returns. Exact three raw
  outputs are in `app/src/test/resources/autoservice/n9-android14-parcels.txt`:
  `00000064` -> integer 100; `4351999a` -> float 209.600006;
  `42c60000` -> float 99.0. The complete private diagnostic log is not published.
- Lines 193-200 show helper shell UID 2000, SELinux rejection of `addService`,
  `READY via=broadcast`, and app `transport=none` with no receiver rejection.
  This proves failed delivery/acceptance, not by itself the exact vendor cause.
- AOSP Android 14 `ContextImpl.sendBroadcast` uses the context's user. The helper
  creates a system context in a shell process (user 0). Passing the full target
  app UID already existed, but broadcast delivery ignored its user. The fix derives
  `UserHandle` from that UID for initial delivery and re-announcements. User-10
  delivery is covered offline; N9 vendor delivery remains a device check.

AOSP references:
[ContextImpl, Android 14](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/ContextImpl.java),
[Shell permissions](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/packages/Shell/AndroidManifest.xml).

## Boundaries and implementation

- Parser accepts whitespace after `Parcel(` while retaining the zero exception
  header, complete scalar framing and existing sentinel handling.
- Broadcast token, interface descriptor and full calling UID checks remain.
- Test Helper service, process, lock, log, package and broadcast action are isolated
  from the official installation. The helper permits only connection bookkeeping
  and scalar GETs in this variant; setter transactions are rejected even if wrapped
  in the read/batch protocol. No new cross-user app permission is requested.
- The N9 manifest replaces the production application and its components. It uses
  a plain Application, one diagnostic Activity and the authenticated Helper
  receiver. There is no production startup, boot service or vehicle-control UI.
- Paths displayed/exported by diagnostics come from the app's current Context;
  export uses Android's document picker. Existing vendor `energydata` paths are not
  redirected speculatively: vendor data may actually reside under user 0. This
  test obtains its battery values through existing read-only autoservice APIs.
- TLS status is observation only. A visible setting or discovered port does not
  prove successful TLS authentication, and an open TCP 5555 socket alone does not
  prove an authorized ADB session.

## Validation

The dedicated Actions workflow runs focused scalar parsing, user routing,
authentication, helper read-only policy and merged-manifest tests, then builds
`:app:assembleN9Test`. It publishes a debug-signed APK, notices, SHA256SUMS and test
reports. Inspect the run's result before using an artifact. Local checks or a
successful build are not evidence that the APK has run on the N9.

Known device-only uncertainties: vendor cross-user broadcast restrictions,
SELinux denial of binder calls, available battery FIDs/scaling, TLS discovery and
authorization, actual user-10 delivery and co-installation on the head unit.
Unsupported cell interfaces show unavailable; no guessed setter/probe is used.

## License

Licensed under PolyForm Noncommercial 1.0.0; see the unchanged root LICENSE,
which is included inside the test APK and the downloadable artifact.

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)
