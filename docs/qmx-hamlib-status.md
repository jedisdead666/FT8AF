# QRP Labs QMX via the hamlib backend: status (PR #925)

Running status for the work on branch `feat/qmx-hamlib-rig` (fork
`laniol8926/FT8AF`), adding a "Qrp Labs QMX (Hamlib)" rig entry backed by
hamlib's real `qrplabs_qmx_caps` instead of hand-rolled Kenwood emulation.
Full narrative, with logs, is in the PR #925 comment thread; this is the
condensed current state.

## Confirmed working (real hardware)

- `rigaddress.txt` entry (`Qrp Labs QMX (Hamlib),809,9600,26`) resolves to
  hamlib's real `RIG_MODEL_QRPLABS_QMX` (`0x809` = 2057) and opens correctly.
- CAT works end-to-end: frequency set from the app matches the radio's own
  displayed frequency after a live change (tested at 7.074 MHz and
  14.074 MHz). (2026-10-21)
- Mode stays in Digital through a frequency change (previously it reverted to
  USB every time). (2026-10-21)
- **RX decodes real FT8 traffic** (2026-10-22, `38f8e9d8`): confirmed on real
  hardware after the PCM16-capture fix below.

## Bugs found and fixed, in order

1. **Wrong mode string produced an invalid CAT command** (`9b5e8787`, after a
   wrong first attempt in `e046e0a7`/`37897125` that was reverted). hamlib's
   QMX backend has no `mode_table` override, so `kenwood_set_mode()` falls
   back to the generic Kenwood table, where `RIG_MODE_PKTUSB` sits at index
   13 — encoded as the letter `'D'` (`"MDD;"`), which the QMX's own CAT
   manual doesn't recognize (only single-digit `MD` values are valid; 6 =
   FSK, the QMX's real digital mode). Fixed by requesting `"RTTY"` instead of
   `"PKTUSB"` — `RIG_MODE_RTTY` sits at table index 6, producing the valid
   `"MD6;"`.
   - The reverted first attempt sent a vendor command believed to be
     `"Q41;"` ("stay in digital mode") after every mode/frequency change.
     Checked against QRP Labs' own official CAT manual
     (`qrp-labs.com/images/qmx/manuals/cat_1_04_003.pdf`): there is no `Q41`
     command. `Q4` is "Get/Set TX Rise Threshold" — `"Q41;"` was silently
     setting it to 1%, an unrelated, actively harmful side effect.

2. **Hamlib model ID silently truncated to a byte on every app restart**
   (`47c8fc4e`). `GeneralVariables.civAddress` was reused for both a genuine
   single-byte ICOM CI-V address and the hamlib model ID (routinely over a
   byte — QMX's `2057`). Its persistence (`CivAddressConfig.encode()`) masks
   with `& 0xFF`, so `2057` round-tripped through disk as `9` — opening an
   unrelated hamlib backend (model 9, SDR#) instead of the QMX on every
   subsequent launch. Fixed with a separate, unmasked `hamlibModelId`
   field/config key.

3. **That fix's migration gap crashed the app** (`4a76f2af`). An install that
   had already selected a Hamlib rig before the new config key existed loaded
   `hamlibModelId = 0`; passing model `0` to hamlib's `rig_init()` aborts the
   whole process (native SIGABRT) about half a second later, not a graceful
   failure. Fixed by refusing to open any `modelId <= 0` in
   `HamlibRig.ensureOpen()`, plus a self-healing migration that derives the
   value from the currently-selected rig on first connect.

4. **The real structural bug: handshake replies were silently dropped**
   (`d78aebe9`). `HamlibRig.onReceiveData()` only forwards radio bytes into
   the native bridge once `handle != 0` — but the original single
   `nativeOpen()` call only set `handle` *after* it returned, and it ran
   hamlib's own `rig_open()` (including its synchronous CAT handshake)
   *internally*, before returning. Every byte the radio sent back during that
   handshake was dropped, so hamlib always timed out waiting for an ack that
   could never arrive — confirmed on real hardware, where `serial.recv`
   showed genuine traffic arriving in the exact window hamlib's own trace
   reported "0 chars". Fixed by splitting the native call into
   `nativeCreate()` (builds the bridge, returns a handle) and
   `nativeRigOpen(handle)` (runs the handshake), with `HamlibRig.ensureOpen()`
   publishing the handle in between.

## RX: a full night's investigation (2026-10-21/22), real root cause still open

A second real-hardware session (Motorola Edge 2022, Android 15, Windows
Remote Control laptop for build/logcat) spent the whole night chasing why RX
showed no waterfall/decodes at all, even once TX/CAT/mode were all confirmed
working the night before. Two real, confirmed, fixed bugs were found along
the way; RX is still not working by the end of the session, with the
investigation narrowed to a specific, actionable, OS-level lead.

### Bugs found and fixed this session

5. **Picking a new input device in Settings never reached the live
   capture** (`5d89ca55`). `RadioAudioSettings.kt`'s device-picker `onSelect`
   persisted the new `audioInputDeviceId`/VID/PID to `GeneralVariables` and
   the DB, but never told the already-running `MicRecorder` to rebuild with
   it — `MicRecorder` only reads those fields once, at construction time.
   The live capture silently kept using whatever it opened with (observed on
   real hardware: the phone's built-in mic) until the next USB attach/detach
   or app restart happened to reopen it. Fixed by calling the existing
   `mainViewModel.reinitializeAudioInput()` from the picker's `onSelect`,
   mirroring the RX-channel-change and Bluetooth-SCO-connect paths, which
   already did this for the same reason. Confirmed in logcat: `writeConfig`
   is now immediately followed by a `MicRecorder` reinit/`preferredDevice`
   line, where before there was none.
6. **A transient `AudioRecord` failure permanently stopped capture for the
   rest of the app session** (`5b76f4b8`). `MicRecorder.reinitialize()`
   decided whether to call `start()` again by reading `isRunning` — but a
   transient `AudioFlinger createRecord_l` failure (observed in the field
   right after a replug/power-cycle, before the audio HAL had caught up)
   sets `isRunning = false` as a side effect of the capture thread's own
   failure-detection path, with no owner-driven `stopRecord()` involved.
   Every `reinitialize()` after that read the now-stale `isRunning` as false
   and silently skipped `start()` — permanently, even once the operator
   picked a completely valid device afterward (confirmed on real hardware:
   `setPreferredDevice()` kept reporting `ok=true deviceFound=true`, and
   `AudioFlinger` genuinely created the record track, but `start()` was
   simply never called again). Fixed with a separate `shouldBeRunning`
   intent flag, touched only by the two owner-facing entry points
   (`start()`/`stopRecord()`) and left alone by transient internal failures;
   `reinitialize()` now keys its restart decision off that instead.

### Mode (USB vs Digi) was a red herring, not the cause

A large part of the night was spent chasing a theory that hamlib's `"RTTY"`
mode string (Digi/`MD6;`, the QMX's literal hardware FSK demodulator)
disables the receive audio chain FT8 needs, vs. `"USB"` (`MD2;`, plain SSB
passband). This looked initially promising — one build swapped to `"USB"`
and a real decode session followed (29 messages, multiple real callsigns) —
but was conclusively ruled out by later, cleaner tests on the same running
app process: switching the radio between USB and Digi at its own front panel
with FT8AF's capture already running and confirmed healthy (continuous HAL
reads, correct device, correct CAT) made no difference — both modes were
silent. `FT_MODE` is back to `"RTTY"` (Digi, the QMX's correct intended mode
for FT8), per `HamlibRig.java`'s own comment, which documents this full
dead-end for future reference.

### Several real leads chased and ruled out before the actual cause

With both of the above bugs fixed, USB capture confirmed genuinely healthy
(MediaTek `AudioALSACaptureHandlerUsb`/`AudioALSACaptureDataProviderUsb`
opening correctly, continuous reads, zero `xrun`/`overrun`/error lines,
correct device id, correct CAT/frequency), decode cycles were still
consistently fast (~140ms, the empty-audio fingerprint vs. ~1100ms when real
audio is present) with zero messages kept, across multiple clean
fresh-launch tests. Two more theories were seriously chased and eventually
ruled out by sharper evidence, in order:

- **OS-level USB-claim theory**: reproduced twice that audio flows through
  Android's own default USB-audio handling right up until an app (FT8AF, and
  also briefly FT8TW) is granted the device via the "choose an app" chooser,
  then stops. Superseded below — not wrong exactly, but not the actual
  mechanism; see the real cause.
- **I/Q mode left on**: `danleetw/FT8TW` issue #78 ("For QMX/QMX+ set I/Q
  mode off", closed as fixed) describes what reads as this exact symptom —
  the QMX's `Q9`/IQ-mode CAT setting streams raw ADC I/Q samples to the USB
  sound card instead of demodulated audio when left on. A side-by-side test
  showed FT8TW (which carries Dan's `Q90;` fix) decoding while FT8AF
  (fixed, pushed as `12815ecb`, sends `Q90;` on every connect) did not —
  **but a later, much more precise test (below) directly disproved I/Q mode
  as this session's actual cause**: the radio decoded fine in Digi/MD6 with
  no `Q90;` ever sent. The `Q90;` fix is being kept anyway since it's a real,
  externally-validated QMX fix for the scenario in that issue — just not
  what was happening on this unit tonight.

### The actual root cause, found and fixed: requesting float capture breaks on this USB audio HAL

A precise, repeated real-hardware A/B, with mode and every other variable
held constant within a single running app process, found it: Android's own
audio HAL log (`AudioALSAStreamIn`'s `set()` call) showed two different
formats being negotiated across different opens of the *same* USB input —
`fmt=5` (`ENCODING_PCM_FLOAT`, what `MicRecorder` was requesting) and
`fmt=1` (`ENCODING_PCM_16BIT`). Every single `fmt=5` open was silent
(zero messages kept, ~140ms decode fingerprint); every single `fmt=1` open
decoded normally (real messages, ~1100ms decode fingerprint) — including
two cases where the *same app process* flipped between the two, unprompted,
across a USB re-enumeration, with CAT mode held at Digi/MD6 the entire time
and decoding starting or stopping in exact lockstep with the format. The
HAL's own log line explains why: `"wrong format(0x5), default use
wordSize = 2"` — it silently falls back to a 16-bit internal word size while
still being asked to hand back 4-byte floats, producing unusable samples
with no error surfaced anywhere.

This also directly explains the two superseded leads above: QFT8 and FT8TW
decoding reliably is consistent with them requesting PCM16 (or getting it
some other way) rather than float; the "audio dies the instant an app
claims the device" observation was likely this same format mismatch
happening to coincide with FT8AF's/FT8TW's own connect sequence, not a
genuine Android USB-claim side effect.

**Fixed and pushed** (`38f8e9d8`): `MicRecorder` now requests
`ENCODING_PCM_16BIT` (matching a commented-out line already present in the
source, suggesting this was the original format before an earlier,
unrelated change switched it to float) and converts to float in the reader
loop via a new `int16ToFloat()` helper, matching the existing
`float2Short()` inverse scale already used on the TX side. Covered by a new
unit test file (`MicRecorderInt16ToFloatTest`).

**CONFIRMED on real hardware (2026-10-22)**: 7 consecutive decode cycles
with real content, every HAL USB capture open at `fmt=1` (PCM16), zero
`"wrong format(0x5)"` warnings across the whole session, radio in Digi mode
(`MD6;`) with `Q90;` also sent on connect per the earlier fix. The RX
investigation that ran all night is fully resolved.

**Known follow-up, not urgent (self-heals)**: the QMX's Android
`AudioDeviceInfo` id changes across every USB replug/re-enumeration (seen
again this session: `id=525` → `deviceFound=false` → operator re-picks →
`id=677` → `deviceFound=true`). `findAudioDeviceById()`/the Settings picker
match by that numeric id, which isn't stable across reconnects, so a stale
persisted id silently falls back to no device until the operator manually
re-selects it in Settings. Matching by product name (or VID/PID, where
available) as a fallback when the persisted id isn't found would remove the
need for that manual re-pick.

## Known issue, not yet root-caused (TX, from 2026-10-21, not re-tested)

TX: the rig keys/transmits, but audio was underdriven (low), with CAT and
mode both confirmed working correctly in that test. Not re-tested this
session since RX never reached a working state to test TX against.

## Known test gaps

- No local build/unit-test execution has been possible on the Linux ARM
  board this work was primarily developed on (`aapt2`/`adb`/`zipalign` are
  all x86_64-only there). Real building and on-device testing for this status
  update were done via a Windows laptop with Android Studio, connected over
  Claude Code's Remote Control feature.
- The Hamlib-backed code paths in `HamlibRigTest.java` are gated behind
  `ensureOpen()`, which always returns false in a JVM unit test (no native
  lib) — consistent with that test file's own documented precedent, hamlib
  behavior itself is verified on real hardware, not in CI unit tests.
