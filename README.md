# BadgeBunny

Two-phone **HID Seos relay** for authorized physical-access testing. Each phone drives a Proxmark3
running the upstream `hf_cardhopper` standalone — RDV4 over Bluetooth Classic (BlueShark), Proxmark5
over BLE (BWM) or USB — and relays the live card↔reader APDU exchange to the other phone over the
network. A door reader authenticates a card that is physically at the other phone.

Seos cannot be cloned; it can be relayed.

## Layout
- `app/` — Android app (Kotlin): Bluetooth Classic, BLE, and USB transport; direct-P2P relay over Tailscale or LAN.
- `firmware/` — Proxmark build configs and PM5 patches (BWM BLE transport + standalone enable).
- `phone/` — device debloat/restore scripts (Galaxy A16).
- `docs/` — architecture and the end-to-end test runbook.

## Quick start
1. Flash both Proxmarks — `firmware/README.md`.
2. Build and install the app — `cd app && ./gradlew installDebug`.
3. Pair the BlueShark (RDV4) or scan for the PM5 over BLE.
4. Run the relay — `docs/TESTING.md`.

## Credits
Builds on the `hf_cardhopper` standalone by Sam Haskins / Loudmouth Security (RfidResearchGroup
Proxmark3). Relay technique per *Unlocking doors from half a continent away* (ePrint 2023/450).

Licensed GPL-3.0. For authorized security testing only.
