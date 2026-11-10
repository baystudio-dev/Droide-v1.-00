#!/usr/bin/env python3
"""Source wiring contract only; run JVM real-child tests separately."""
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/baystudio/droide/core/LocalLinuxProcessHost.kt"

class ReviewedProcessAdoptionWiring(unittest.TestCase):
    def check_route(self, route, identity):
        source = SOURCE.read_text()
        start = source.index("override suspend fun " + route + "(")
        next_override = source.find("\n    override ", start + 1)
        body = source[start:next_override if next_override >= 0 else len(source)]
        self.assertNotIn("LocalHostedProcess(builder.start()", body)
        self.assertEqual(1, body.count("createOwnedLocalHostedProcess("))
        self.assertEqual(1, body.count("start = { builder.start() }"))
        self.assertIn("scope = scope,", body)
        self.assertIn("cleanup = { LocalExecutionSubstrate.terminateLease(lease) }", body)
        self.assertIn("withReviewed" + identity + "LaunchIdentity<LocalHostedProcess>", body)
        creation = body.index("createOwnedLocalHostedProcess(")
        self.assertLess(body.index("val admitted = admitStart {"), creation)
        self.assertLess(body.index("selectionAdmission {"), creation)
        self.assertLess(body.rindex("scope.ensureActive(); creationContext.ensureActive()", 0, creation), creation)
        self.assertRegex(body[creation:], r"\)\.also \{\s+created = it\s+ExitHostedProcessRegistry\.retain\(it\)")
        self.assertIn("check(admitted === created)", body)
        self.assertIn("try { created?.close() }", body)
        self.assertIn("return ExitHostedProcessRegistry.admit(process, epoch)", body)

    def test_sqruff(self):
        self.check_route("startReviewedSqruffInWorkspace", "Sqruff")

    def test_svelte(self):
        self.check_route("startReviewedSvelteInWorkspace", "Svelte")

    def test_powershell(self):
        self.check_route("startReviewedPowerShellInWorkspace", "PowerShell")

if __name__ == "__main__":
    unittest.main(verbosity=2)
