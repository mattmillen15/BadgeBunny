# Research

Background and references for the BadgeBunny Seos relay.

## Prior work

- **Unlocking doors from half a continent away** (ePrint 2023/450) — demonstrated practical relay
  of HID Seos credentials using Proxmark3 and the `hf_cardhopper` standalone. Established that Seos
  mutual authentication completes over a relayed link when the emulator's ATS inflates the Frame
  Waiting Index (FWI) to cover the round-trip.

- **hf_cardhopper** (RfidResearchGroup/proxmark3, Sam Haskins / Loudmouth Security) — standalone
  applet that implements both the reader side (`select_card` → relay APDUs) and emulator side
  (`become_card` with forged ATS → relay APDUs). Handles ISO 14443-3 anticollision and RATS locally;
  the host sees only post-handshake Seos frames.

- **hopper.py** (hf_cardhopper reference host) — serial relay host that wires two Proxmarks over
  UART/USB. BadgeBunny's `RelayEngine` mirrors this: `run_reader()` → `runReader()`,
  `run_card()` → `runEmulator()`, same frame ordering and RESTART/ACK protocol.

## Key constraints

**Seos cannot be cloned.** The credential performs SCP03 mutual authentication with the reader on
every tap. Both sides must be live and online during the exchange — there is no offline replay.

**Timing is the binding constraint.** The real card's frame-waiting time (FWI=9 ≈ 155 ms on tested
HID Seos cards) bounds how long the reader side can stall before the card drops the ISO-DEP session.
The cooked ATS on the emulator side inflates FWI to 14 (≈ 4.95 s), so the door reader waits, but
the card does not. A phone-to-phone round-trip above ~155 ms causes the card to time out mid-auth
and the session collapses. Tailscale DERP relays add ~196 ms; direct WireGuard or LAN links measure
~30-40 ms and work reliably.

**The emulator must not relay RATS.** The door reader's RATS is answered locally by the firmware
with the cooked ATS. The reader-side card was already activated by `select_card()`, so a second RATS
returns ERR. The host must consume the ERR to keep the pump aligned but must not push it back to the
firmware — the extra USB round-trip makes the firmware miss the reader's next command and collapses
the session.

## Transport notes

| Transport | Proxmark | Protocol | Reliability |
|---|---|---|---|
| Bluetooth Classic (SPP) | RDV4 + BlueShark | RFCOMM, UUID `00001101-...`, PIN 1234 | Stable; native socket reads block indefinitely (not interruptible by `Thread.interrupt()`) |
| BLE (BWM) | PM5 (ESP32-C2) | GATT service 0xAE86, data char 0xAE88 | Functional; abrupt GATT disconnect can wedge the ARM-ESP32 UART bridge (requires power cycle) |
| USB-CDC | PM5 or RDV4 | OTG, CDC-ACM | Most reliable; requires `-DCARDHOPPER_USB` firmware build for direct USB framing |

## Observed behavior

- A successful Seos relay completes 6-8 APDU round-trips: SELECT AID, SCP03 initiate, two rounds of
  EXTERNAL/MUTUAL AUTHENTICATE, and a final MAC exchange. The door grants access on the last `90 00`.
- After a successful auth, the door reader re-polls (sends another RATS). The real card's ISO-DEP
  session is over, so the reader side returns ERR. This is normal — the relay re-arms for the next tap.
- The relay is inherently retry-until-success: each tap is independent, and a single collapsed session
  (link blip, slow RTT spike, RF coupling loss) just means "try again."
- PM5 BWM BLE is more volatile than RDV4 BlueShark SPP. Force-stopping the app without a clean GATT
  disconnect wedges the PM5's internal UART forwarding. Always use the STOP button or `bb_cmd stop`.
