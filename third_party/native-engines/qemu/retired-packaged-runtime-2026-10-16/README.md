# Retired Alpine/QEMU distribution evidence

The current runtime uses Ubuntu Base through PRoot. No QEMU or Alpine payload is part of the current Android assets or native libraries. Optional support for user-supplied external QEMU tools is independent of these retired bundles.

On 2026-11-09 the five large historical files (78,678,902 bytes) were removed from the active source checkout. They are retained byte-for-byte in the previously backed-up stable source ZIP identified by `EXTERNAL_ARCHIVE.json`. The ZIP named `Droide-Full-Source-2026-11-09-landscape-build-ready.zip` has the same archive hash and is also accepted. This reduces source distribution size; those payloads were already absent from the APK.

`pre-retirement/` retains the original notices, package inventories, source offer and historical scripts. The parent directory retains the small aports recipes and patches. `RETIREMENT.json` is the unchanged 2026-10-16 record: its paths identify the historical tree inside the archived ZIP, not files required by today's build. Frozen historical build manifests elsewhere in the repository likewise describe their original snapshots. Do not rewrite their receipts or execute the archived generator against the current tree.

Current checks verify the Ubuntu/PRoot runtime, retained historical metadata and the archive index without requiring legacy payloads. To additionally verify every removed byte from the maintainer's stable backup, run from the project root:

```sh
python3 -B tools/verify_runtime_licenses.py \
  --historical-source-zip /path/to/Droide-Full-Source-STABIL-BUILD-SUCCESS-2026-11-09.zip
```

Verification reads the ZIP without extracting or executing its contents. Use a separate historical checkout when inspecting or rebuilding an old release. Do not restore these payloads into current source or Android asset roots.

The corresponding-source offer remains applicable to previously distributed versions. Obtain the retained archive from the maintainer using `app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md`; no expiry or withdrawal is implied by removing unused payloads from the current checkout. Existing device data, recovery journals and user-installed tools are unaffected.
