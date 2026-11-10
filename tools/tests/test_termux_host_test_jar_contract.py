"""Small pure archive-policy fixtures. No javac, AAR build, JNI or PTY is invoked."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

RECIPE = Path(__file__).resolve().parents[1] / "build_termux_host_test_jar.py"
SPEC = importlib.util.spec_from_file_location("termux_test_only_recipe", RECIPE)
assert SPEC and SPEC.loader
recipe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recipe)


class TermuxHostTestJarContractTest(unittest.TestCase):
    def setUp(self) -> None:
        # Deliberate format fixtures, not compiled Java and not a build receipt.
        self.hashes = {"fixture/source.java": "a" * 64}
        self.original = {name: b"original" for name in recipe.REPLACEMENTS - recipe.ADDITIONS}
        self.original[recipe.PREFIX + "Unrelated.class"] = b"must remain byte identical"
        self.entries = dict(self.original)
        self.entries.update({name: b"\xca\xfe\xba\xbe\x00\x00\x00\x34fixture" for name in recipe.REPLACEMENTS})
        self.entries[recipe.MARKER] = recipe.json_bytes(recipe.marker(self.hashes))

    def validate(self) -> dict[str, str]:
        return recipe.validate_jar(recipe.deterministic_jar(self.entries), self.hashes, self.original)

    def test_exact_test_only_scope_is_accepted(self) -> None:
        result = self.validate()
        self.assertEqual(set(self.entries), set(result))
        self.assertFalse(recipe.marker(self.hashes)["releaseCompatible"])

    def test_missing_test_only_marker_is_rejected(self) -> None:
        del self.entries[recipe.MARKER]
        with self.assertRaisesRegex(SystemExit, "unexpected or missing entry"):
            self.validate()

    def test_stale_source_identity_is_rejected(self) -> None:
        self.hashes["fixture/source.java"] = "b" * 64
        with self.assertRaisesRegex(SystemExit, "stale or non-TEST-ONLY"):
            self.validate()

    def test_owned_abi_source_identity_cannot_be_silently_changed(self) -> None:
        path = "third_party/termux/native/OWNED_PROCESS_ABI.json"
        self.hashes[path] = "c" * 64
        self.entries[recipe.MARKER] = recipe.json_bytes(recipe.marker(self.hashes))
        self.hashes[path] = "d" * 64
        with self.assertRaisesRegex(SystemExit, "stale or non-TEST-ONLY"):
            self.validate()

    def test_owned_helpers_and_jni_are_in_the_explicit_class_set(self) -> None:
        for name in ("JNI", "TerminalProcessOwner", "TerminalProcessOwner$Backend",
                     "TerminalPtyIo", "TerminalPtyIo$ReaderBackend", "TerminalPtyIo$WriteReceipt"):
            self.assertIn(recipe.PREFIX + name + ".class", recipe.REPLACEMENTS)
        self.assertEqual(26, len(recipe.REPLACEMENTS))
        self.assertNotIn(recipe.PREFIX + "JNI.class", recipe.ADDITIONS)

    def test_release_compatible_marker_is_rejected(self) -> None:
        marker = recipe.marker(self.hashes)
        marker["releaseCompatible"] = True
        self.entries[recipe.MARKER] = recipe.json_bytes(marker)
        with self.assertRaisesRegex(SystemExit, "non-TEST-ONLY"):
            self.validate()

    def test_unrelated_class_change_is_rejected(self) -> None:
        self.entries[recipe.PREFIX + "Unrelated.class"] = b"changed"
        with self.assertRaisesRegex(SystemExit, "unrelated original entry changed"):
            self.validate()

    def test_native_entry_is_rejected(self) -> None:
        self.entries["jni/arm64-v8a/libtermux.so"] = b"not a real native binary"
        with self.assertRaisesRegex(SystemExit, "unexpected or missing entry"):
            self.validate()

    def test_unreviewed_added_class_is_rejected(self) -> None:
        self.entries[recipe.PREFIX + "Extra.class"] = b"extra"
        with self.assertRaisesRegex(SystemExit, "unexpected or missing entry"):
            self.validate()

    def test_changed_bytecode_target_is_rejected(self) -> None:
        self.entries[recipe.PREFIX + "TerminalSession.class"] = b"\xca\xfe\xba\xbe\x00\x00\x00\x3dfixture"
        with self.assertRaisesRegex(SystemExit, "not Java 8 class bytecode"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
