# N9 / Android 14 read-only battery build (505)

This branch belongs to `Komachi418/BYDMate`. It is a diagnostic test build, not a
production vehicle-control release. No vehicle write/control tests are permitted.

## Install and use

- Install the delivered locally signed **BYDMate-v3.19.9-n9-battery1.apk**.
  The fork's **N9 Android 14 read-only APK (unsigned for local signing)** Actions
  artifact is a build intermediate and requires the retained local key before it
  can be installed. Do not install an APK whose filename ends in `-unsigned.apk`.
- This revision is **com.bydmate.app.n9test2**, labelled **BYDMate N9 电池**,
  version code **505**. It retains Test 2's local signing certificate and package
  so it can update Test 2 if that package is already installed.
  It can also coexist with the first test APK (`com.bydmate.app.n9test`). The
  first CI build used an ephemeral debug signing key; Test 2 starts a separate
  installation with a retained test signing key so later Test 2 builds can update
  it. Do not uninstall either the official app or the first test to install Test 2.
  Keep the official `com.bydmate.app` installed. Never uninstall it or clear its
  data to install this test. Its existing WRITE_SECURE_SETTINGS grant is untouched.
- Run only while parked. Keep the official app responsible for its existing ADB
  recovery. This package does not enable wireless debugging, restore port 5555,
  grant permissions, pair TLS ADB, or issue vehicle controls.
- Open **BYDMate N9 电池** and press **读取电池并保存**. The main screen uses
  the direct classic 5555 / autoservice path already exercised on the user's N9
  in the first test. It displays SOC, SoH, min/max cell voltage, cell delta and the
  cumulative energy counter. A separate ADB
  authorization prompt may appear because this package owns a separate key.
- Each button press reads once and saves the result, including unavailable fields
  and failed attempts, into the app's private storage. The last 20 attempts survive
  reopening the app. Previously saved values are labelled as history; starting a
  new read clears the displayed values until that attempt completes. A failed read
  must never substitute an old value or a numeric zero. Partial results retain only
  the fields that actually succeeded. Local save failures are visible.
- Copy or export the retained readings and diagnostic report as TXT. The export
  includes timestamps and original raw Parcels. There is no automatic upload,
  background polling, or startup ADB connection. Sequential fields are a snapshot
  collected over time, not an atomic battery measurement.
- Optional Helper comparison and TLS observation remain in the expandable
  diagnostic area. A Helper failure does not block the battery screen. No head-unit
  restart or force-stop is needed for the normal reading flow.
- The diagnostic report includes Android userId, classic 5555 connection,
  TLS observation, autoservice probe/raw Parcel, SOC/SoH, cell readings and Helper
  transport. Unsupported/unavailable values must remain unavailable, not zero.
- Helper comparison retains raw direct Parcels and Helper per-item status/raw bits.
  A missing/invalid read stays unavailable, even when both paths fail. SAME means
  both valid reads agreed for that pair; DIFFERENT can reflect change between the
  sequential readings and is not by itself a defect or a health conclusion. A
  failed Helper must not hide the direct battery read. Do not change SELinux,
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
  delivery was initially covered offline; the subsequent device result is below.

The subsequent N9 report `bydmate_n9_1791378677929.txt` (SHA-256
`185ae05b58620d0e56da5363ea1468f976268f70c96275388fa73f08d920162f`)
provided the first successful device check on 2026-10-07:

- Android user 10 and both app storage paths were confirmed.
- Classic 5555 ADB connected and the tab-containing Parcels decoded to SoH 100%,
  SOC 98%, lifetime counter 209.90001, min/max cells 3323/3330 mV (delta 7 mV).
- `addService` was still denied by SELinux, but broadcast Binder delivery, ping,
  version 503 and client registration succeeded (`registerClient=0` is success).
- That first test read battery values through direct ADB. It did not exercise
  Helper battery reads. Version 505 uses the proven direct path for its battery
  screen; Helper comparison remains an optional, unverified device check. Reboot
  recovery and sustained operation remain separate device checks.
- TLS was only observed; no TLS authentication or pairing was attempted. No
  vehicle write/control test was performed. Single readings are not an independent
  battery capacity or calibration assessment.

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
`:app:assembleN9Test`. CI produces an explicitly unsigned APK, notices,
SHA256SUMS and test reports. The installable delivery is signed and verified locally
with a retained dedicated test key. The private key stays outside the repository
and artifacts; the unsigned CI APK is not directly installable. Local checks or a
successful build are not evidence that this new APK has run on the N9.

The first N9 APK installed successfully and used APK Signature Scheme v2 only.
The user reported that Test 2 was rejected by the head-unit installer. Test 2 has
valid v1, v2 and v3 signatures; a default verifier selecting v3 does not mean its
v2 signature is absent. The installer rejection's exact cause remains unknown.
Version 505 deliberately uses v2-only signing like the first installed APK, with
the retained Test 2 certificate. This is a compatibility choice, not proof that
the installer problem is fixed. Verify signature, certificate, package/version,
ZIP integrity, page alignment, unchanged CI payload and embedded license locally.

Remaining device checks: installation and main screen/history/export on the N9,
Helper-to-autoservice reads and comparison,
recovery after process/head-unit restarts, long-term availability, and TLS discovery
and authorization. First-test evidence does not establish every firmware's FID
meaning/scaling or all cross-user permissions.
Unsupported cell interfaces show unavailable; no guessed setter/probe is used.

## License

Licensed under PolyForm Noncommercial 1.0.0; see the unchanged root LICENSE,
which is included inside the test APK and the downloadable artifact.

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)
