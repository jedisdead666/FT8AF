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

## Known issue, not yet root-caused

TX: the rig keys/transmits, but audio is underdriven (low), with CAT and mode
both confirmed working correctly in the same test. Not yet investigated —
some earlier audio findings in the PR thread (the libusb I/O write failure,
the "OS never registers a sound card" finding, the `BOTH`-vs-`LEFT` channel
routing default) were drawn while CAT was *also* silently broken by bug #4
above, so they may not all still apply as previously understood and are
worth re-checking fresh now that CAT and mode are both reliable.

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
