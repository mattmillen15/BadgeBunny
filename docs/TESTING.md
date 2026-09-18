# Test runbook

Relay a live HID Seos authentication between two phones so a door reader authenticates a card that
is physically at the other phone. RDV4 side is Bluetooth Classic, PM5 side is BLE (BWM) or USB.
Authorized testing only.

## Prerequisites
- Both Proxmarks flashed with `hf_cardhopper` (`firmware/README.md`).
- BadgeBunny installed on both phones.
- Tailscale signed in on both phones (same tailnet), or both on the same Wi-Fi.

## Physical setup
| | Card side (reads the badge) | Reader side (goes to the door) |
|---|---|---|
| Phone | A | B |
| Proxmark | RDV4 + BlueShark (paired `PM3_RDV4.0`, PIN 1234) | PM5 (wireless via BLE or wired via USB-OTG) |
| Present | Seos card on the RDV4 antenna | PM5 to the door reader |

Keep the BlueShark battery mid-charge and the card-antenna coupling tight.

## Config

Phone A (RDV4 / Card side):
1. Role: **Card side -- reads the real badge**.
2. Proxmark: **BT (RDV4)** -> Scan -> select `PM3_RDV4.0` -> Connect.
3. Peer IP: enter Phone B's IP (Tailscale `100.x.x.x` or Wi-Fi).

Phone B (PM5 / Reader side):
1. Role: **Reader side -- goes to the door**.
2. Proxmark:
   - **BLE (PM5)** (preferred): Scan (5 s BLE scan) -> select the PM5 -> Connect.
   - **USB (PM5)** (fallback): Plug PM5 via USB-OTG -> Scan -> select it -> Connect.
3. Peer IP: enter Phone A's IP.

Card parameters (tagType=SEOS, FWI=14, SFGI=0) and port (8099) are hardcoded in the app.

## Run
1. START RELAY on both phones (any order; the connector retries until the listener is up).
2. Present the Seos card to the RDV4 -- the log shows UID and ATS captured.
3. Present the PM5 to the door reader -- APDUs relay and the reader authenticates.

## Entering standalone mode
The PM5 must be in CardHopper standalone mode for the relay to work:
- **From the app**: connecting over BLE or USB triggers standalone automatically.
- **Button**: hold the PM5 side button for 1 second (LED A lights solid).
- **Client**: `./pm3 -c "hw standalone"` (if USB-connected to a computer).

## Troubleshooting
- **PM5 not showing on BLE scan**: ensure the PM5 is powered on and the BWM firmware is flashed
  (`firmware/README.md`). The PM5 advertises as a BLE device with service UUID 0xAE86.
- **Peer never connects**: confirm both phones are on the tailnet, the peer IP is correct, and
  Tailscale is actually up.
- **No UID/ATS**: reposition the card; check the BlueShark battery.
- **Reader times out**: FWI is already 14 (max); tighten the RF coupling; keep the network path
  direct (same Wi-Fi or Tailscale direct connection).
- **PM5 USB fallback**: it exposes two CDC interfaces; the app selects the higher-index one.
