# N9 / Android 14 battery-only application (506)

This work is confined to `Komachi418/BYDMate`, branch `n9-android14`. The independent
package remains `com.bydmate.app.n9test2`, labelled **BYDMate N9 电池**, version
`3.19.9-n9-battery2` (506). Keep the official `com.bydmate.app` and its existing
WRITE_SECURE_SETTINGS authorization. No vehicle write/control tests are permitted.

## What the application does

The launcher is a dedicated five-page battery app: overview, cells/health,
charging records, trip energy, and recording/settings. It uses the original
BYDMate's dark blue/green visual vocabulary without loading its production
Application, navigation, Hilt graph, tracking service, automation, or controls.
The former N9 diagnostic screen remains a separate optional entry point.

Open the app once and enable automatic recording. Thereafter, while the head unit
is powered and classic ADB is reachable, the foreground monitor takes a read-only
sample roughly every 15 seconds plus acquisition time. Charging and trip records
start from observed state changes; no per-charge start button is required. A
persistent notification provides a stop action. The saved enable switch permits
resumption at boot/app reopening; explicit stop persists disabled. New installs
start disabled and a one-shot refresh never silently enables monitoring.

Foreground operation does not guarantee sampling through vendor power management,
head-unit shutdown or loss of ADB. No wake lock is acquired, and no code tries to
keep the vehicle powered. Gaps and process restarts interrupt records instead of
inventing data. The only added system permissions are foreground service,
notifications and boot delivery for the explicitly enabled monitor.

## Data and interpretation

Thirty fixed existing FidMap GET addresses cover SOC, BMS SoH, cell voltage
extremes, battery temperatures, pack voltage/current, remaining energy, low-voltage
battery, insulation, BMS power limits, charging state/counter, odometer/speed,
front/rear motor current, compressor power and supplementary battery states.
Six derived fields include cell/temperature spread and voltage-current power.
No arbitrary command, setter, catalog scan, or guessed adjacent FID is used.

**Only five raw fields have N9 device evidence so far:** SOC, SoH, the cumulative
energy counter and min/max cell voltage. Cell delta follows from those voltages.
All other fields, multipliers, current signs and state mappings remain unverified
on this N9. A plausible value is not proof of the mapping. Missing/sentinel/error
responses stay unavailable; current readings never borrow a historical value.

Readings are sequential rather than an atomic bus snapshot. Derived power and
integrated energy are estimates based on the existing mapping:

- Pack power is voltage times current; positive denotes outflow, negative inflow
  under the existing source mapping. On a PHEV, inflow is not necessarily braking
  regeneration; it may include another energy source.
- Front/rear motor electrical energy and air-conditioning compressor energy are
  independent available components. Compressor energy is not all HVAC energy.
  Components are not forced to add up to pack outflow, and no unmeasured residual
  is labelled as a measured loss or other consumption.
- Distance uses valid odometer increments with the exact fixed-address scale.
  Missing distance is not silently replaced by a speed-derived estimate.
- Each component has its own coverage. Intervals with missing endpoints, clock
  discontinuities or excessive gaps are not integrated. Positive/negative power
  crossing within an interval is split at zero rather than integrated as abs(P).
- The ambiguous cumulative energy counter is never used as charging energy or a
  cycle count. ChargingCapacity is retained as an interface reading, not assumed
  to be a continuous session meter.

## Automatic sessions and health records

Charging requires two consistent samples with BMS charging state and supported
plug evidence. SOC increase or negative pack power alone never starts a charge.
VTOL/external-discharge gun state is explicitly excluded. Pause does not create a
new charge, and absent/unknown plug values do not fabricate unplug events.
Charging completion or confirmed unplug ends the observed charge; interruptions
remain distinguishable. A record describes the observed interval, which may not
be the entire physical charging session.

Trips start on observed speed above 3 km/h. Charging or ten minutes of observed
stationary time ends a trip segment. Long sampling gaps interrupt it. Trip details
include available kilometers, battery outflow/inflow/net energy, front/rear motor
and compressor energy, per-component coverage, and the source limitations.

The health page compares BMS SoH, SOC windows, cell spread and temperatures across
charging records. It does not diagnose capacity or calibration from a single
reading. Nominal capacity has **no default vehicle value**. A charging-interval
capacity estimate is only available for a completed uninterrupted interval with
at least 20 percentage points of SOC increase, adequate measured coverage and
positive net input energy. The formula net input / SOC increase * 100 is a trend
proxy, not a measurement of usable discharge capacity or a definitive SoH.
Tariff-derived cost is an estimate from recorded battery energy, not charger-billed
energy. Unsupported cell arrays/indices, temperature probe arrays and charging
remaining time are not invented.

The independent private SQLite store retains bounded samples and session summaries.
CSV exports samples and summaries; TXT provides summaries and diagnostics. The
latest raw responses are retained for mapping investigation. The old 505 private
history remains available from the diagnostic screen. Raw car logs, private
signing material and personal recordings are never committed or published.

## Root causes and existing N9 evidence

The complete original private `bydmate_logs_20261007_185755.txt` was recovered and
checked locally: 24,211 bytes, SHA-256
`bbe03154e7267ad9b60e41d4a34e6c215344c270d4de6adfb0fa443fcfc5cfd4`.
Literal whitespace after `Parcel(` defeated the previous scalar parser. The
regression fixtures retain actual raw replies for `00000064` -> 100,
`4351999a` -> 209.600006, and `42c60000` -> 99.0. Complete framing, exception status,
interface descriptor, token and full-UID authentication remain checked.

The helper's system-context broadcast used user 0 despite the target application
being on user 10. Initial broadcast and re-announcements now use the user derived
from the complete target UID. No SELinux rules, root or permissive mode changed.
Service, process, lock, log, action and package identities remain isolated.

The follow-up `bydmate_n9_1791378677929.txt`, SHA-256
`185ae05b58620d0e56da5363ea1468f976268f70c96275388fa73f08d920162f`, showed Android 14 /
SDK 34 / user 10, authenticated classic 5555 and SoH 100%, SOC 98%, cumulative
counter 209.90001, cell voltages 3323/3330 mV and delta 7 mV. ServiceManager was
still denied, but broadcast Binder delivery, ping and registration succeeded.
Helper battery forwarding, sustained operation and TLS authentication were not
proven by that test. The main app uses direct ADB GETs; Helper/TLS checks are optional.

## Build and installation

The fork's Actions workflow runs the selected parsing, authentication, multi-user,
read-only, manifest, telemetry, recording, lifecycle and UI regression tests,
then assembles an explicitly **unsigned** intermediate. Do not install an APK
ending in `-unsigned.apk`. The delivered `BYDMate-v3.19.9-n9-battery2.apk` is signed
locally with the retained Test 2 key; the key never leaves this task's local storage.
Version 506 updates 505/Test 2 and coexists with the official app and first N9 test.

The first N9 test installed and used v2-only signing. Test 2's installer rejection
is still unexplained; its v1/v2/v3 signatures were valid. This build uses v2-only
signing with the retained Test 2 certificate. Signature/payload/CRC/page-alignment
and license checks do not guarantee acceptance by the vendor installer.
Installation and all newly added N9 fields, automatic states, background lifetime,
trip units and calculated summaries still require actual vehicle verification.
There are no vehicle-control tests and no production startup services.

Android platform references for the foreground-monitor implementation:
[Starting a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch),
[Android 14 type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required),
[Doze limits](https://developer.android.com/training/monitoring-device-state/doze-standby).

## License

The unchanged LICENSE is embedded in the APK and delivered alongside it.
PolyForm Noncommercial 1.0.0.
Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)
