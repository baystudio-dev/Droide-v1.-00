# Android device bridge source

This directory contains the Android-only Kadb 2.1.4 derivative compiled by the application. Modified source implements bounded connection cancellation, required TLS, authenticated pairing identity and bounded shell output.

## Source and licensing

`PROVENANCE.json` identifies the original source archive and runtime JAR. `UPSTREAM_SOURCE_MANIFEST.json` records the original source entries. `UPSTREAM_PATCH.diff` describes the derivative changes, and `DERIVATIVE_SHA256SUMS.txt` covers the derivative sources. Original copyright, license and modification notices remain intact. Kadb is Apache-2.0; the separate SPAKE2 component retains its BSD notices.

Preserve upstream checksums separately from derivative hashes. When changing sources, regenerate and review the derivative patch and checksums, run affected device-bridge tests, and build the application.

## Integration

`app/build.gradle.kts` adds this directory's `src/main/kotlin` to the main application source roots. The original Kadb runtime JAR is not added alongside these sources. The mDNS compatibility dependency remains a separate artifact. Support dependencies are pinned and locked by the application build.

## Behavioral contracts

- Wireless Debugging requires TLS and validates pairing identity from authenticated peer data.
- Cancellation closes attempt-owned sockets and prevents late publication of a closed connection.
- Queue failure wakes waiters; transport close cannot wait indefinitely for protocol cleanup.
- Shell output limits are enforced before allocating a peer-declared payload.

This component does not create a standalone APK or native library. Validate the application on the intended Android devices, including pairing failure, cancellation, reconnect and process shutdown.
