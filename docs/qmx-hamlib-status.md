# QRP Labs QMX via the hamlib backend: status (PR #925)

Running status for the work on branch `feat/qmx-hamlib-rig` (fork
`laniol8926/FT8AF`), adding a "Qrp Labs QMX (Hamlib)" rig entry backed by
hamlib's real `qrplabs_qmx_caps` instead of hand-rolled Kenwood emulation.
Full narrative, with logs, is in the PR #925 comment thread; this is the
condensed current state.

## Confirmed working (real hardware, 2026-10-21)

- `rigaddress.txt` entry (`Qrp Labs QMX (Hamlib),809,9600,26`) resolves to
  hamlib's real `RIG_MODEL_QRPLABS_QMX` (`0x809` = 2057) and opens correctly.
- CAT works end-to-end: frequency set from the app matches the radio's own
  displayed frequency after a live change (tested at 7.074 MHz and
  14.074 MHz).
- Mode stays in Digital through a frequency change (previously it reverted to
  USB every time).

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

### Current state and the real remaining lead

With both of the above bugs fixed, USB capture confirmed genuinely healthy
(MediaTek `AudioALSACaptureHandlerUsb`/`AudioALSACaptureDataProviderUsb`
opening correctly, 48kHz/24-bit stereo, thousands of continuous reads, zero
`xrun`/`overrun`/error lines, correct device id, correct CAT/frequency),
decode cycles were still consistently fast (~140ms, the empty-audio
fingerprint vs. ~1100ms when real audio is present) with zero messages kept,
across multiple clean fresh-launch tests and both CAT modes.

**The clearest lead found all night**: a third-party app, QFT8 (known from
elsewhere in this project to work correctly against this exact
phone+radio), decodes normally and immediately whenever it's given the
device. Directly observed on real hardware, reproduced twice independently
(not a one-off): audio was flowing (visible as waterfall traces) through the
*OS's own* default handling of the QMX's USB Audio Class interface in the
window right after a power-cycle, *before* the Android "choose an app"
USB-device chooser was answered — and it stopped the instant FT8AF was
selected in that chooser, both times. hamlib's QMX-specific open
function (`qrplabs_open` in the vendored `ts480.c`) only queries firmware
version on connect, nothing audio-related, so this isn't something FT8AF's
CAT traffic does — it looks like simply Android granting FT8AF raw
`UsbDeviceConnection` access to the composite device detaches it from
whatever OS-level audio path was supplying real samples, independent of
which audio-input option is selected in Settings and independent of CAT
mode. QFT8 apparently doesn't trigger this (or recovers from it); the
difference in exactly *how* it opens/handles the device is the open
question. Also considered and not yet ruled out: the QMX's own `AG`
(AF Gain/volume) CAT command, which hamlib's QMX backend does not implement
at all (`qrplabs_qmx_caps` has no `.has_set_level`/`.set_level`) — FT8AF
has never set this on any code path, so if a power-cycle resets it low and
QFT8's own connect sequence sets it, that would independently explain
silence-despite-healthy-capture too. The two leads aren't mutually
exclusive and both need a real logcat-instrumented test to settle.

**Not yet attempted**: comparing exactly what Android USB APIs/permission
calls QFT8 makes vs. FT8AF's `CdcAcmSerialDriver`/`UsbAudioDevice` (QFT8's
author has declined to share source, so this has to be black-box
experimentation — e.g. instrumenting exactly when/how FT8AF calls
`UsbManager.openDevice()`/`requestPermission()` relative to when audio
stops, and trying "Always" vs "Just once" in the chooser to see if that
changes anything), and sending a raw `AG` CAT command on connect as a
cheap, independent test of the AF-gain theory.

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
