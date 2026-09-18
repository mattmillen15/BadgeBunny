# Firmware

Both Proxmarks run the upstream `hf_cardhopper` standalone from RfidResearchGroup/proxmark3. This
directory holds only the platform config and the Proxmark5 patch; clone the Proxmark3 repo to build.

## Build

RDV4 (Bluetooth / BlueShark):

    cp Makefile.platform.rdv4 <proxmark3>/Makefile.platform
    cd <proxmark3> && make clean && make -j fullimage

Proxmark5 (USB): `hf_cardhopper` requires `WITH_FPC_USART_HOST`, which the PM5 does not provide. The
patch drops that requirement when `-DCARDHOPPER_USB` is set (the relay then runs over USB-CDC, not
the FPC USART).

    git -C <proxmark3> apply <this>/hf_cardhopper-pm5-usb.patch
    cp Makefile.platform.pm5 <proxmark3>/Makefile.platform
    cd <proxmark3> && make clean && make -j fullimage

## Flash

RDV4 (AT91) — standard client flash over USB:

    ./pm3-flash-fullimage armsrc/obj/fullimage.elf

Proxmark5 (AT32) — if the bootloader predates the modern flash protocol, use DFU:

    cd <proxmark3> && make -j recovery
    # hold the button while plugging into the button-side USB port until the LEDs go off (DFU mode)
    sudo dfu-util -d 2e3c:df11 -a 0 -s 0x08000000:leave -D recovery/recovery.bin
    # unplug and replug without the button to boot the new firmware

Verify: `./pm3` → `hw status` should list the `HF - Long-range relay 14a ... CardHopper` standalone.
