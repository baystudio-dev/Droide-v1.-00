# Android device bridge

Droide uses the Android source derivative of Kadb 2.1.4 for wireless debugging, pairing, shell, synchronization, APK installation, log access and forwarding. The application compiles `droide-source/src/main/kotlin` directly. The retained original runtime JAR is provenance material; it is not an additional application implementation dependency.

The mDNS compatibility component remains a pinned local Maven dependency. Supporting dependency versions and verification metadata are declared in the application build and lockfile. `tools/verify_third_party_integrity.py` checks the retained component files.

The derivative preserves cancellation, TLS enforcement and authenticated pairing identity. ADB host identity is stored through Droide's encrypted credential storage. See [the derivative documentation](droide-source/README.md) for source mapping and build boundaries.

Kadb retains Apache-2.0. The separate SPAKE2 compatibility component retains BSD-3-Clause and its own source, notices and test vectors. Preserve all upstream attribution and corresponding source when redistributing.
