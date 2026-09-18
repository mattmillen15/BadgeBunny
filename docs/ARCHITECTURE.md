# Architecture

## Overview
Two Proxmarks, two phones. One Proxmark reads the real Seos card (`READ` mode); the other emulates
a card to the target reader (`CARD` mode) with a forged ATS. Only the post-RATS Seos APDUs cross
the network — the ISO 14443-3 handshake is handled locally on each Proxmark, and the emulator
inflates the ATS Frame Waiting time (FWI) so the reader tolerates the round-trip.

```
 real card ⇄ RDV4 (READ) ⇄ phone A ⇄── IP ──⇄ phone B ⇄ PM5 (CARD) ⇄ target reader
               BT / USB                        BLE / USB
```

## Cardhopper serial protocol
Transport: raw serial over BlueShark BT-SPP, or USB-CDC when built with `-DCARDHOPPER_USB`.
Frame: `[len:1][payload:len]`, len 1..255. Each host→PM3 frame is ACKed with `0xFE` (`0xFF"ERR"`
on overrun); PM3→host frames are not ACKed. Reference: `armsrc/Standalone/hf_cardhopper.c`.

Modes (host→PM3): `READ`, `CARD`, `RESTART`, `\xFF"END"`.
- `READ`: the PM3 selects the card and returns UID then ATS, then relays each APDU it is given and
  returns the card's response.
- `CARD`: the PM3 is given tagType, `[FWI,SFGI]`, UID, ATS; it answers the 14443-3 handshake and
  RATS locally (ATS cooked with the supplied FWI) and relays post-handshake APDUs.

## Transports
- Proxmark ↔ phone: three options, all sharing `CardhopperCodec` framing via the `Pm3Link` interface:
  - **Bluetooth Classic SPP/RFCOMM** — RDV4 + BlueShark add-on (`Pm3Bluetooth`).
  - **BLE GATT (Nordic UART Service)** — PM5 built-in BLE module (`Pm3Ble`). The module bridges
    USART ↔ BLE transparently; the cardhopper serial framing runs unchanged over the NUS RX/TX
    characteristics. MTU is negotiated up to 247 for throughput; writes are chunked to MTU-3.
  - **USB-CDC** — PM5 over USB-OTG (`Pm3Usb`). Requires firmware built with `-DCARDHOPPER_USB`.
- Phone ↔ phone: direct TCP, one side listens and one connects, over Tailscale or LAN. `TCP_NODELAY`
  is set and there is no relay-server hop; the connector auto-reconnects until the peer is up.

## App modules (`app/app/src/main/java/com/badgebunny/`)
- `transport/` — `Pm3Link`, `CardhopperCodec`, `Pm3Usb`; `bt/Pm3Bluetooth`, `bt/Pm3Ble`.
- `net/RelayLink` — framed TCP between phones.
- `relay/RelayEngine` — the READER and EMULATOR state machines.
- `MainActivity` — UI.

## Hardware
- Proxmark3 RDV4 + BlueShark add-on (Bluetooth).
- Proxmark5 (USB; AT32, DFU-flashed).
- Two Android phones.

## Timing
Seos readers reject slow cards, but the pre-crypto ATS is unauthenticated, so the emulator sets FWI
up to 14 (≈4.95 s). The binding constraint is the real card's own timeout, so keep the network path
short (Tailscale direct or LAN) and the RF coupling tight.
