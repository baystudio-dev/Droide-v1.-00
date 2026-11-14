# Kadb 2.1.4 Android-only Droide source derivative

This directory is a 2026-10-15 semantic reconstruction of bounded connection cancellation, required TLS and authenticated pairing-GUID handling. It is not the exact lost source linkage or an unchanged upstream release.

## Inputs and licensing

The original source archive, compatibility JAR, Maven metadata, checksums, and `third_party/kadb/README.md` remain unchanged. `PROVENANCE.json` identifies them by SHA-256. `UPSTREAM_SOURCE_MANIFEST.json` records every original source entry. Original source copyright/license comments are preserved, including mobile.dev's queue notice. Every modified or newly added Kotlin file carries a dated Droide derivative notice; the 30 unchanged source files retain exact archive bytes. Kadb is Apache-2.0; a full copy is included. The existing separate SPAKE2 compatibility component and its BSD notices are unchanged.

`UPSTREAM_PATCH.diff` is a standard unified diff against the archive entries mapped to `src/main/kotlin/` by `UPSTREAM_SOURCE_MANIFEST.json`, with explicit no-final-newline markers. It records all derivative changes, including removal of common `expect` declarations and `actual` modifiers from the retained Android implementations, and moving the default device-name argument. `DERIVATIVE_SHA256SUMS.txt` covers all production derivative sources. Regenerate the derivative patch and checksums after reviewed changes; never replace original upstream checksums with derivative hashes.

## Explicit source integration

The reviewed integration adds exactly `../third_party/kadb/droide-source/src/main/kotlin` to the app's `main` Java/Kotlin source roots. It removes `com.baystudio.compat:kadb-android-compat:2.1.4` from implementation to avoid duplicate classes. The original JAR is retained only as provenance. The unchanged `kadb-mdns-android-compat:2.1.4` remains a dependency.

All support dependencies retain their existing versions/checksums: Kotlin 2.4.0, Okio 3.17.0, coroutines 1.11.0, DocumentFile 1.1.0, HiddenApiBypass 6.1, SPAKE2 compatibility 1.0.0 and Bouncy Castle bcprov/bcpkix/bcutil 1.84. SPAKE2 and BC become explicit compile dependencies because their callers now compile from source. Lockfile configuration memberships must be reconciled without dropping checksum or license verification.

This source directory creates no standalone AAR, APK, native output or Gradle subproject. It uses the existing reviewed app JVM compilation/test task graph. Packaging/native/device execution remains held. At staging time the build owner applies and verifies the shared build hunk; source staging alone is not proof of production linkage.

## Added contracts

- `KadbOptions.requireTls` rejects unencrypted AUTH/CNXN on the Wireless Debugging path and stays active across lazy retries.
- `Kadb.isTlsConnection()` reports the actual published TLS transport state, not mDNS metadata.
- `Kadb.cancel()` is terminal and closes attempt-owned raw sockets before any potentially blocked protocol writer cleanup.
- `KadbConnectionControl` registers raw sockets before TCP connect and pairing handshake; late registration is immediately closed.
- `Kadb.pairWithResult()` returns only a bounded valid GUID from the authenticated decrypted PeerInfo. Existing `pair()` remains available.
- A failed queue read wakes all waiters; TLS close aborts underlying I/O instead of blocking on close-notify.
- `AdbShellStream.read(Int)` checks a caller-supplied output budget at header decode, before allocating the peer-declared payload. The original JVM `read()` remains available. The app passes the remaining cumulative 16 KiB probe-output budget.

The platform hostname resolver may ignore thread interruption. Cancellation still closes the raw socket, bounds the caller's wait and prevents a late resolver result from initiating TCP or publishing a session. It cannot guarantee the DNS syscall itself terminates. The app runs both pairing and probes behind a fixed two-worker/two-queued executor, so cancelling noncooperative work releases its coroutine mutex while bounding stranded DNS workers. Two stuck resolvers can exhaust the workers; newer calls still time out and remove queued work rather than spawning unlimited threads. Numeric mDNS addresses avoid this resolver path. No live network tests are used for this reconstruction.

The existing server trust manager is unchanged. Required encryption plus paired-GUID discovery filtering is not a new certificate-pinning guarantee.

## Tests

App-host tests compile these production sources directly. `KadbReconstructionSafetyTest` exercises the TLS state gate, pairing result validation, cancellation ownership, and the production TCP factory with fake Socket/resolver implementations that never use a network. The suite also uses fake raw streams for blocked read/write cancellation, tests queue-reader failure wakeup and bounded shell packet decode. The manager/policy/attempt/UI suites cover selected app integration paths. Tests were authored but not executed by the source owner; snapshot compilation, independent review and test results are separate evidence. Production STLS dispatch, actual SSLEngine handshake completion and stale-transport retry integration are not exercised by these fakes; the TLS tests exercise the extracted production state gate. The two Compose cases test the isolated existing-button control, not full settings row/disposal navigation. Device-level TLS behavior still needs future authorized acceptance testing.
