#!/usr/bin/env python3
"""Check transfer permissions in source and, optionally, Android merged manifests.

JobScheduler's network-permission enforcement is not supplied by Robolectric's
scheduler shadow. Validate this admission contract before a source release and
against the manifests produced by Android's merger in CI.
"""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
REQUIRED_PERMISSIONS = (
    "INTERNET", "ACCESS_NETWORK_STATE", "RUN_USER_INITIATED_JOBS",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_SPECIAL_USE",
)
JOB_ENABLED = "user_initiated_transfer_jobs_enabled"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_manifest(path):
    document = ET.parse(path).getroot()
    require(document.tag == "manifest", f"Not an Android manifest: {path}")
    declarations = document.findall("uses-permission")
    for short_name in REQUIRED_PERMISSIONS:
        name = "android.permission." + short_name
        matches = [p for p in declarations if p.get(ANDROID + "name") == name]
        require(any(p.get(ANDROID + "maxSdkVersion") is None and
                    p.get(TOOLS + "node") != "remove" for p in matches),
                f"{path}: missing uncapped permission {name}")
    application = document.find("application")
    require(application is not None, f"{path}: application missing")
    services = {s.get(ANDROID + "name", ""): s for s in application.findall("service")}

    def service(short_name):
        choices = (".core." + short_name, "com.baystudio.droide.core." + short_name)
        found = next((services[name] for name in choices if name in services), None)
        require(found is not None, f"{path}: transfer service {short_name} missing")
        require(found.get(ANDROID + "exported") == "false", f"{path}: {short_name} must remain private")
        return found

    job = service("UserInitiatedArtifactTransferJobService")
    require(job.get(ANDROID + "permission") == "android.permission.BIND_JOB_SERVICE",
            f"{path}: job service must require BIND_JOB_SERVICE")
    require(job.get(ANDROID + "enabled") == "@bool/" + JOB_ENABLED,
            f"{path}: UIDT job service must use the Android 14 resource gate")
    for name, expected_type in (("PackageTransferForegroundService", "dataSync"),
                                ("DevelopmentForegroundService", "specialUse")):
        declared = service(name).get(ANDROID + "foregroundServiceType", "").split("|")
        require(expected_type in declared, f"{path}: {name} missing foreground service type {expected_type}")
    return {"path": str(path), "permissionsChecked": list(REQUIRED_PERMISSIONS),
            "jobServicePermission": "BIND_JOB_SERVICE", "jobServiceResourceGate": JOB_ENABLED}


def verify_resource_gate():
    for qualifier, expected in (("values", "false"), ("values-v34", "true")):
        path = ROOT / "app/src/main/res" / qualifier / "transfer_config.xml"
        matches = [b for b in ET.parse(path).getroot().findall("bool") if b.get("name") == JOB_ENABLED]
        require(len(matches) == 1 and (matches[0].text or "").strip() == expected,
                f"{path}: expected {JOB_ENABLED}={expected}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, action="append", default=[],
                        help="Additional merged or candidate manifest; repeat for each variant")
    args = parser.parse_args()
    try:
        verify_resource_gate()
        paths = [ROOT / "app/src/main/AndroidManifest.xml", *args.manifest]
        reports = [verify_manifest(path) for path in dict.fromkeys(paths)]
    except (OSError, ET.ParseError, ValueError) as failure:
        raise SystemExit(f"ARTIFACT_TRANSFER_MANIFEST_FAILED: {failure}") from None
    print(json.dumps({"manifests": reports, "legacyForegroundTransfer": True, "uidtMinimumApi": 34}, indent=2))
    print("ARTIFACT_TRANSFER_MANIFEST_OK")


if __name__ == "__main__":
    main()
