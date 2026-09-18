# Phone setup (Galaxy A16)

Enable USB debugging (Settings → About phone → tap Build number ×7 → Developer options → USB
debugging), then authorize the host over adb.

Debloat — per-user and reversible; `packages.txt` is the removal list:

    ./debloat.sh     # remove
    ./rebloat.sh     # restore

Install the app:

    cd ../app && ./gradlew installDebug

Networking: install Tailscale and sign in on both phones (same tailnet), or put both on the same
Wi-Fi. The app's peer link takes the other phone's address.
