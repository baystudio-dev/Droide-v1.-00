# Droide Android JGit reconstruction

This is a modified Android-specific derivative of Eclipse JGit 6.10.1.202505221210-r, upstream commit 9d12b89dd9e7c17eb958d923dde4001995c47a5c. It is not published or endorsed by Eclipse. The new coordinate is com.baystudio.compat:jgit-android-compat:6.10.1.202505221210-r-droide-reconstruction1; it does not identify the lost historical derivative.

Changes: Android real-process PID for unchanged GC locking; no desktop JMX registration/annotation (statistics preserved); no SPNEGO/Kerberos GSS implementation (Basic/Digest preserved). Monitoring and GSSManagerFactory public optional APIs are absent. This is not a full desktop JGit ABI-compatible replacement.

All upstream source copyright headers, about.html (Eclipse Distribution License 1.0/BSD-3-Clause and bundled MIT notice), translations and unrelated entries are preserved. UPSTREAM_ABOUT.html is the complete upstream notice. The original signed JAR is retained unchanged under upstream/. Derivative signatures and obsolete manifest digests are removed; the original manifest is preserved solely as attribution in META-INF/droide-jgit/UPSTREAM.MF.

Reconstruction sources and patch were prepared on 2026-10-15. No artifact, runtime certification or lost-checkpoint equivalence is implied by source preparation. Future build provenance is REBUILD.json and must be verified against actual inputs/output.
