# Firmware

Both Proxmarks run the upstream `hf_cardhopper` standalone from RfidResearchGroup/proxmark3. This
directory holds only the platform configs and patches; clone the Proxmark3 repo to build.

## Patches

Three patches are needed for PM5:

- **`hf_cardhopper-pm5-usb.patch`** — drops the `WITH_FPC_USART_HOST` requirement from `Makefile.hal`
  when `-DCARDHOPPER_USB` or `-DCARDHOPPER_BWM` is set, so `hf_cardhopper` builds on PM5.
- **`pm5-enable-standalone.patch`** — removes the `#ifndef PM5` guard in `appmain.c` that prevents
  standalone modes from running on PM5 (upstream disables them while BWM is being debugged).
- **`hf_cardhopper-pm5-bwm.patch`** — adds `-DCARDHOPPER_BWM` transport to `hf_cardhopper.c`, routing
  relay data through the BWM BLE channel (`bwm_fwd_writebuffer_sync` / `bwm_read_ng`).

## Build

RDV4 (Bluetooth / BlueShark):

    cp Makefile.platform.rdv4 <proxmark3>/Makefile.platform
    cd <proxmark3> && make clean && make -j fullimage

Proxmark5 (BLE relay via BWM):

    git -C <proxmark3> apply <this>/hf_cardhopper-pm5-usb.patch
    git -C <proxmark3> apply <this>/pm5-enable-standalone.patch
    git -C <proxmark3> apply <this>/hf_cardhopper-pm5-bwm.patch
    cp Makefile.platform.pm5 <proxmark3>/Makefile.platform
    cd <proxmark3> && make clean && make -j fullimage

The PM5 config enables `PLATFORM_EXTRAS=BWM` alongside `STANDALONE=HF_CARDHOPPER` with
`-DCARDHOPPER_BWM`. Relay data flows over the BWM BLE channel (UART4/app_com, wrapped in app_com
framing by `bwm_fwd_writebuffer_sync`). The phone connects to the PM5 over BLE using the BWM GATT
service (0xAE86) and bidirectional data characteristic (0xAE88).

## Flash

RDV4 (AT91) — standard client flash over USB:

    ./pm3-flash-fullimage armsrc/obj/fullimage.elf

Proxmark5 (AT32) — use DFU if the client can't communicate:

    cd <proxmark3> && make -j recovery
    # hold the button while plugging into the button-side USB port until the LEDs go off (DFU mode)
    sudo dfu-util -d 2e3c:df11 -a 0 -s 0x08000000:leave -D recovery/recovery.bin
    # unplug and replug without the button to boot the new firmware

If the client can communicate, standard flash works too:

    ./pm3 --flash --image armsrc/obj/fullimage.elf

After flashing with BWM, upgrade the ESP32-C2 module firmware:

    ./pm3 -c "hw bwm upgrade"

Verify: `./pm3` -> `hw status` should list the `HF - Long-range relay 14a ... CardHopper` standalone.
