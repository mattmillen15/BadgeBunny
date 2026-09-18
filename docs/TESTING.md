# Test runbook

Relay a live HID Seos authentication between two phones so a door reader authenticates a card that
is physically at the other phone. RDV4 side is Bluetooth, PM5 side is USB. Authorized testing only.

## Prerequisites
- Both Proxmarks flashed with `hf_cardhopper` (`firmware/README.md`).
- BadgeBunny installed on both phones.
- Tailscale signed in on both phones (same tailnet), or both on the same Wi-Fi.

## Physical setup
| | Reader side (at the card) | Emulator side (at the reader) |
|---|---|---|
| Phone | A | B |
| Proxmark | RDV4 + BlueShark (paired `PM3_RDV4.0`, PIN 1234) | PM5 via BLE or USB-OTG |
| Present | Seos card on the RDV4 antenna | PM5 to the door reader |

Keep the BlueShark battery mid-charge and the card↔antenna coupling tight.

## Config
Phone A (RDV4 / Reader / listener):
- Transport Bluetooth → Refresh → `PM3_RDV4.0` → Connect.
- Role Reader. Network Tailscale. Listen on, port 8099.

Phone B (PM5 / Emulator / connector):
- **BLE (preferred)**: Transport BLE → Refresh (scans for 5 s) → select the PM5 → Connect.
- **USB fallback**: Plug the PM5 in via USB-OTG → Transport USB → Refresh → select it → Connect
  (grant USB permission).
- Role Emulator. Card SEOS, FWI 14, SFGI 0.
- Network Tailscale. Listen off. Peer = `<phone A 100.x>:8099`.

## Run
1. START RELAY on both (any order; the connector retries until the listener is up).
2. Present the Seos card to the RDV4 — the log shows UID and ATS captured.
3. Present the PM5 to the reader — APDUs relay (`→card`/`←card`, `←reader`/`→reader`) and the reader
   authenticates.

## Troubleshooting
- Peer never connects: confirm both phones are on the tailnet, the peer IP is correct, and Tailscale
  is actually up (the app shows "Tailscale not up" otherwise).
- No UID/ATS: reposition the card; check the BlueShark battery.
- Reader times out: FWI is already 14 (max); tighten the RF coupling; keep the network path direct.
- PM5 won't talk: it exposes two CDC interfaces; the app selects the higher-index one — reconnect.
