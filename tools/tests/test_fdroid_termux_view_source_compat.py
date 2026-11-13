"""Pure data mutations for the two frozen View metadata compatibility rules."""
from __future__ import annotations

from dataclasses import FrozenInstanceError, replace
import hashlib
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import fdroid_termux_view_compat as compat
import fdroid_termux_view_metadata as metadata
from test_fdroid_termux_view_source_metadata import fixture, method, u2


FIRST, SECOND = (compat._RULES[path] for path in compat.REVIEWED_CLASSES)
DEFAULT = object()


def sample(rule, *, parameters=DEFAULT, signature=DEFAULT, access=0, descriptor=None,
           field_name="untouched", seed=None, extra_constructor_attrs=(), extra_methods=(),
           code=b"\xb1", code_attrs=(), **options):
    parameters = rule.modern if parameters is DEFAULT else parameters
    signature = rule.removed_signature if signature is DEFAULT else signature
    row = method(name="<init>", access=access, descriptor=descriptor or rule.descriptor,
                 code=code, code_attrs=code_attrs, attrs=extra_constructor_attrs)
    if parameters is not None:
        row["parameters"] = parameters
    if signature is not None:
        row["signature"] = signature
    row.update(options)
    return fixture(name=rule.class_path[:-6], seed=seed,
                   fields=[{"name": field_name, "descriptor": "I", "access": 0x19, "constant": 7},
                           {"name": "literal", "descriptor": "Ljava/lang/String;", "access": 0x19, "constant": "fullEditor"}],
                   methods=[row, method(name="unrelated", access=2), *extra_methods],
                   attrs=[("SourceFile", lambda pool: u2(pool.utf("Unrelated.java")))])


def attribute_position(member, name):
    offset = member.attributes_start + 2
    for attribute in member.attributes:
        if attribute.name == name:
            return offset, attribute
        offset += len(attribute.raw)
    raise AssertionError("fixture attribute missing")


class FrozenCompatibilityTests(unittest.TestCase):
    def test_only_exact_immutable_public_identities_and_hashes_are_admitted(self):
        self.assertEqual(dict((path, rule.raw_sha256) for path, rule in compat._RULES.items()), {
            "com/termux/view/TerminalView$1$1.class": "fdfc286756a17ceceff219c34373251bb28074f545aac6c9991d7ee60ff4e869",
            "com/termux/view/TerminalView$2.class": "e3acbbd955ae645baa2ca28e2de58ad92cbdc8e7dab93f73973a2175eba28081"})
        with self.assertRaises(TypeError):
            compat._RULES["new.class"] = FIRST
        with self.assertRaises(FrozenInstanceError):
            FIRST.constructor_access = 1
        with self.assertRaisesRegex(ValueError, "unreviewed compatibility rule"):
            compat._adapt_reviewed(sample(FIRST), replace(FIRST))
        for identity in ("TerminalView$1$1.class", "other.class", "../" + FIRST.class_path, None):
            with self.subTest(identity=identity), self.assertRaisesRegex(ValueError, "unknown class identity"):
                compat.adapt_class(identity, sample(FIRST))
        with self.assertRaisesRegex(ValueError, "wrong class identity"):
            compat.adapt_class(FIRST.class_path, sample(SECOND))
        for rule in (FIRST, SECOND):
            with self.subTest(path=rule.class_path), self.assertRaisesRegex(ValueError, "SHA-256 changed"):
                compat.adapt_class(rule.class_path, sample(rule))

    def test_first_rule_swaps_only_parameter_names_and_removes_exact_signature(self):
        raw = sample(FIRST)
        after, evidence = compat._adapt_reviewed(raw, FIRST)
        before_frame, after_frame = compat._frame(raw), compat._frame(after)
        self.assertEqual(before_frame.pool_bytes, after_frame.pool_bytes)
        self.assertEqual(before_frame.pool_count, after_frame.pool_count)
        ctor = compat._constructor(after_frame, FIRST, adapted=True)
        attrs = {attr.name: attr for attr in ctor.attributes}
        self.assertNotIn("Signature", attrs)
        self.assertEqual(compat._parameters(attrs["MethodParameters"], after_frame.pool), FIRST.historical)
        self.assertEqual(len(evidence["changedAttributes"]), 2)
        self.assertTrue(metadata.api_difference(raw, after))
        self.assertEqual(after, compat._adapt_reviewed(raw, FIRST)[0])

    def test_second_rule_appends_only_fixed_missing_utf8_in_order(self):
        for existing, additions in (((), ("x0", "x1")), (("x0",), ("x1",)), (("x1",), ("x0",)), (("x0", "x1"), ())):
            with self.subTest(existing=existing):
                raw = sample(SECOND, seed=lambda pool: [pool.utf(name) for name in existing])
                after, evidence = compat._adapt_reviewed(raw, SECOND)
                old, new = compat._frame(raw), compat._frame(after)
                self.assertEqual(new.pool_count, old.pool_count + len(additions))
                self.assertEqual(new.pool_bytes, old.pool_bytes + b"".join(b"\1\0\2" + name.encode("ascii") for name in additions))
                self.assertEqual(evidence["appendedUtf8Constants"], list(additions))
                self.assertEqual(after, compat._adapt_reviewed(raw, SECOND)[0])
                self.assertEqual(len(evidence["changedAttributes"]), 1)

    def test_shared_parameter_field_and_string_constants_are_never_renamed(self):
        raw = sample(SECOND, field_name="targetView")
        old = compat._frame(raw)
        after, _ = compat._adapt_reviewed(raw, SECOND)
        new = compat._frame(after)
        self.assertEqual(tuple(row.raw for row in old.fields), tuple(row.raw for row in new.fields))
        for name in ("targetView", "fullEditor"):
            index = compat._utf8_indices(old)[name]
            self.assertEqual(new.pool.entries[index], old.pool.entries[index])
        self.assertEqual(new.fields[0].name, "targetView")
        ctor = compat._constructor(new, SECOND, adapted=True)
        params = next(attr for attr in ctor.attributes if attr.name == "MethodParameters")
        self.assertEqual(compat._parameters(params, new.pool), SECOND.historical)

    def test_success_evidence_is_raw_candidate_preservation_not_baseline_equivalence(self):
        raw = sample(SECOND)
        after, evidence = compat._adapt_reviewed(raw, SECOND)
        for key in ("existingConstantPoolPreserved", "allCodeAttributesPreserved", "unrelatedClassStructurePreserved", "proofOnly"):
            self.assertIs(evidence[key], True)
        self.assertIs(evidence["referenceBinaryUsed"], False)
        self.assertIs(evidence["productionArtifactUpdated"], False)
        self.assertEqual(evidence["class"], SECOND.class_path)
        self.assertEqual(evidence["descriptor"], SECOND.descriptor)
        self.assertEqual(evidence["rawSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(evidence["adaptedSha256"], hashlib.sha256(after).hexdigest())
        self.assertIn("not historical Code or runtime equivalence", evidence["preservationScope"])

    def test_wrong_preimage_parameters_flags_signature_and_constructor_are_rejected(self):
        for rule in (FIRST, SECOND):
            cases = [dict(parameters=None), dict(parameters=rule.modern[:-1]),
                     dict(parameters=rule.modern[:1] + rule.modern[1:][::-1]),
                     dict(parameters=(("wrong", rule.modern[0][1]), *rule.modern[1:])),
                     dict(parameters=((rule.modern[0][0], 0), *rule.modern[1:])),
                     dict(access=1), dict(descriptor="(III)V"), dict(signature="(I)V"), dict(no_code=True),
                     dict(extra_constructor_attrs=[("Deprecated", b"")]),
                     dict(extra_methods=[method(name="<init>", descriptor="()V", access=0)]),
                     dict(extra_constructor_attrs=[("MethodParameters", b"\0")])]
            if rule.removed_signature is not None:
                cases.extend((dict(signature=None), dict(extra_constructor_attrs=[("Signature", lambda p: u2(p.utf("()V")))])))
            else:
                cases.append(dict(signature="()V"))
            for options in cases:
                with self.subTest(rule=rule.class_path, options=options), self.assertRaises(ValueError):
                    compat._adapt_reviewed(sample(rule, **options), rule)
        with self.assertRaisesRegex(ValueError, "wrong class identity"):
            compat._adapt_reviewed(sample(SECOND), FIRST)

    def test_truncation_and_double_application_are_rejected(self):
        for rule in (FIRST, SECOND):
            raw = sample(rule)
            for invalid in (raw[:-1], raw[:9], raw + b"x"):
                with self.subTest(rule=rule.class_path, size=len(invalid)), self.assertRaises(ValueError):
                    compat._adapt_reviewed(invalid, rule)
            after, _ = compat._adapt_reviewed(raw, rule)
            with self.assertRaisesRegex(ValueError, "already adapted"):
                compat._adapt_reviewed(after, rule)
            with self.assertRaisesRegex(ValueError, "already adapted"):
                compat.adapt_class(rule.class_path, after)

    def test_pool_overflow_rejects_without_wrapping_indices(self):
        base_count = int.from_bytes(sample(SECOND)[8:10], "big")
        raw = sample(SECOND, seed=lambda pool: [pool.utf("padding_" + str(i)) for i in range(65535 - base_count)])
        self.assertEqual(int.from_bytes(raw[8:10], "big"), 65535)
        with self.assertRaisesRegex(ValueError, "constant-pool overflow"):
            compat._adapt_reviewed(raw, SECOND)

    def test_independent_certificate_rejects_non_code_member_and_class_mutations(self):
        raw = sample(SECOND)
        after, _ = compat._adapt_reviewed(raw, SECOND)
        frame = compat._frame(after)
        field_offset = frame.fields[0].attributes_start - 6
        changed_field = after[:field_offset] + u2(0x11) + after[field_offset + 2:]
        changed_header = after[:frame.pool_end] + u2(0x1031) + after[frame.pool_end + 2:]
        method_offset = frame.methods[1].attributes_start - 6
        changed_method = after[:method_offset] + u2(1) + after[method_offset + 2:]
        for changed in (changed_field, changed_header, changed_method):
            with self.subTest(changed=changed[-12:]), self.assertRaises(ValueError):
                compat._certify_adaptation(raw, changed, SECOND)
        # Same valid class identity and modern constructor, but a non-Code field
        # modifier changed: public frozen-preimage admission must still reject.
        before_frame = compat._frame(raw)
        field_offset = before_frame.fields[0].attributes_start - 6
        changed_raw = raw[:field_offset] + u2(0x11) + raw[field_offset + 2:]
        with self.assertRaisesRegex(ValueError, "SHA-256 changed"):
            compat.adapt_class(SECOND.class_path, changed_raw)

    def test_independent_certificate_rejects_code_debug_and_stack_data_changes(self):
        cases = [(b"\xb1", ("LineNumberTable", u2(1) + u2(0) + u2(10)), b"\x0b"),
                 (b"\0\xb1", ("StackMapTable", u2(1) + b"\0"), b"\1")]
        for code, nested, replacement in cases:
            with self.subTest(attribute=nested[0]):
                raw = sample(FIRST, code=code, code_attrs=[nested])
                after, _ = compat._adapt_reviewed(raw, FIRST)
                frame = compat._frame(after)
                position, attr = attribute_position(frame.methods[0], "Code")
                # Valid debug/stack-map change ignored by the ordinary API.
                offset = position + len(attr.raw) - 1
                mutated = after[:offset] + replacement + after[offset + 1:]
                self.assertEqual(metadata.api_difference(after, mutated), {})
                with self.assertRaisesRegex(ValueError, "Code attribute changed"):
                    compat._certify_adaptation(raw, mutated, FIRST)

    def test_independent_certificate_rejects_pool_rewrite_and_extra_append(self):
        raw = sample(SECOND, field_name="targetView")
        after, _ = compat._adapt_reviewed(raw, SECOND)
        frame = compat._frame(after)
        position = frame.pool_bytes.index(b"targetView") + 10
        changed = after[:position] + b"TargetView" + after[position + len(b"targetView"):]
        with self.assertRaisesRegex(ValueError, "constant pool changed"):
            compat._certify_adaptation(raw, changed, SECOND)
        changed = (after[:8] + u2(frame.pool_count + 1) + frame.pool_bytes + b"\1\0\2x2" + after[frame.pool_end:])
        with self.assertRaisesRegex(ValueError, "constant pool changed"):
            compat._certify_adaptation(raw, changed, SECOND)

    def test_independent_certificate_rejects_parameter_flag_and_other_attribute_changes(self):
        raw = sample(SECOND)
        after, _ = compat._adapt_reviewed(raw, SECOND)
        frame = compat._frame(after)
        position, attr = attribute_position(frame.methods[0], "MethodParameters")
        # Change the last otherwise-valid flags from 0 to FINAL.
        offset = position + len(attr.raw) - 2
        changed = after[:offset] + u2(16) + after[offset + 2:]
        with self.assertRaisesRegex(ValueError, "MethodParameters"):
            compat._certify_adaptation(raw, changed, SECOND)
        changed = after[:-len(frame.class_attributes)] + u2(0)
        with self.assertRaisesRegex(ValueError, "unrelated class/field"):
            compat._certify_adaptation(raw, changed, SECOND)
        constructor = frame.methods[0]
        attrs = constructor.attributes
        changed = (after[:constructor.attributes_start] + u2(len(attrs)) +
                   b"".join(attr.raw for attr in reversed(attrs)) + after[constructor.end:])
        with self.assertRaisesRegex(ValueError, "attribute order/count"):
            compat._certify_adaptation(raw, changed, SECOND)


if __name__ == "__main__":
    unittest.main()
