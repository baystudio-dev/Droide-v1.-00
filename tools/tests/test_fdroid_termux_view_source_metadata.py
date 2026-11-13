"""Parse-only View class metadata fixtures; never load or execute JVM bytecode."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import fdroid_termux_view_metadata as metadata
import java_archive


def u2(value):
    return struct.pack(">H", value)


def u4(value):
    return struct.pack(">I", value)


def mutf8(value):
    units, result = value.encode("utf-16-be", errors="surrogatepass"), bytearray()
    for offset in range(0, len(units), 2):
        unit = int.from_bytes(units[offset:offset + 2], "big")
        if 1 <= unit < 0x80:
            result.append(unit)
        elif unit < 0x800:
            result.extend((0xc0 | (unit >> 6), 0x80 | (unit & 63)))
        else:
            result.extend((0xe0 | (unit >> 12), 0x80 | ((unit >> 6) & 63), 0x80 | (unit & 63)))
    return bytes(result)


class PoolFixture:
    def __init__(self):
        self.rows, self.indices = [], {}

    def add(self, tag, data, key=None):
        key = (tag, data) if key is None else key
        if key not in self.indices:
            self.indices[key] = len(self.rows) + 1
            self.rows.append(bytes((tag,)) + data)
            if tag in {5, 6}:
                self.rows.append(b"")
        return self.indices[key]

    def utf(self, value):
        data = mutf8(value)
        return self.add(1, u2(len(data)) + data)

    def cls(self, value):
        return self.add(7, u2(self.utf(value)))

    def integer(self, value):
        return self.add(3, struct.pack(">i", value))

    def constant(self, kind, value):
        if kind in "BCISZ":
            return self.integer(value)
        if kind == "J":
            return self.add(5, struct.pack(">q", value))
        if kind in {"F", "D"}:
            return self.add(4 if kind == "F" else 6, bytes.fromhex(value))
        if kind == "Ljava/lang/String;":
            return self.add(8, u2(self.utf(value)))
        raise AssertionError("unsupported fixture constant")

    def nat(self, name, descriptor):
        return self.add(12, u2(self.utf(name)) + u2(self.utf(descriptor)))

    def ref(self, owner, name, descriptor, tag=9):
        return self.add(tag, u2(self.cls(owner)) + u2(self.nat(name, descriptor)))

    def handle(self, kind=6):
        ref = self.ref("example/Bootstrap", "make", "()Ljava/lang/Object;", 10)
        return self.add(15, bytes((kind,)) + u2(ref))

    def indy(self, bootstrap=0):
        return self.add(18, u2(bootstrap) + u2(self.nat("get", "()Ljava/lang/Object;")))

    def attrs(self, rows):
        result = bytearray(u2(len(rows)))
        for name, data in rows:
            data = data(self) if callable(data) else data
            result.extend(u2(self.utf(name)) + u4(len(data)) + data)
        return bytes(result)


def element(pool, value):
    tag, payload = value
    if tag in "BCDFIJSZ":
        raw = u2(pool.constant(tag, payload))
    elif tag in {"s", "c"}:
        raw = u2(pool.utf(payload))
    elif tag == "e":
        raw = u2(pool.utf(payload[0])) + u2(pool.utf(payload[1]))
    elif tag == "@":
        raw = annotation(pool, payload)
    elif tag == "[":
        raw = u2(len(payload)) + b"".join(element(pool, item) for item in payload)
    else:
        raw = payload
    return tag.encode("ascii") + raw


def annotation(pool, value):
    descriptor, pairs = value
    return (u2(pool.utf(descriptor)) + u2(len(pairs)) +
            b"".join(u2(pool.utf(name)) + element(pool, item) for name, item in pairs))


def annotations(values):
    return lambda pool: u2(len(values)) + b"".join(annotation(pool, value) for value in values)


def code_payload(pool, code, *, max_locals=256, max_stack=64, exceptions=(), attrs=()):
    code = code(pool) if callable(code) else code
    return (u2(max_stack) + u2(max_locals) + u4(len(code)) + code + u2(len(exceptions)) +
            b"".join(struct.pack(">HHHH", *row) for row in exceptions) + pool.attrs(attrs))


def fixture(*, name="com/termux/view/Fixture", fields=(), methods=(), attrs=(), seed=None,
            access=0x31, interfaces=(), minor=0, major=52):
    pool = PoolFixture()
    if seed:
        seed(pool)
    this, parent = pool.cls(name), pool.cls("java/lang/Object")
    interface_bytes = b"".join(u2(pool.cls(value)) for value in interfaces)

    def member(row, method=False):
        rows = list(row.get("attrs", ()))
        descriptor, flags = row["descriptor"], row.get("access", 0)
        if "signature" in row:
            rows.append(("Signature", u2(pool.utf(row["signature"]))))
        if "constant" in row:
            rows.append(("ConstantValue", u2(pool.constant(descriptor, row["constant"]))))
        if "exceptions" in row:
            rows.append(("Exceptions", u2(len(row["exceptions"])) +
                         b"".join(u2(pool.cls(value)) for value in row["exceptions"])))
        if "parameters" in row:
            parameters = row["parameters"]
            rows.append(("MethodParameters", bytes((len(parameters),)) +
                         b"".join(u2(pool.utf(value) if value is not None else 0) + u2(modifiers)
                                  for value, modifiers in parameters)))
        if method and not flags & (0x100 | 0x400) and not row.get("no_code"):
            default = b"\xb1" if descriptor.endswith("V") else b"\x03\xac"
            rows.append(("Code", code_payload(pool, row.get("code", default),
                         max_locals=row.get("max_locals", 256), max_stack=row.get("max_stack", 64),
                         exceptions=row.get("handlers", ()), attrs=row.get("code_attrs", ()))))
        header = u2(flags) + u2(pool.utf(row["name"])) + u2(pool.utf(descriptor))
        return header + pool.attrs(rows)

    field_bytes = b"".join(member(row) for row in fields)
    method_bytes = b"".join(member(row, True) for row in methods)
    tail = (u2(access) + u2(this) + u2(parent) + u2(len(interfaces)) + interface_bytes +
            u2(len(fields)) + field_bytes + u2(len(methods)) + method_bytes + pool.attrs(attrs))
    return b"\xca\xfe\xba\xbe" + u2(minor) + u2(major) + u2(len(pool.rows) + 1) + b"".join(pool.rows) + tail


def method(**values):
    return {"name": "run", "descriptor": "()V", "access": 1, **values}


def annotated(pairs, descriptor="Lexample/Metadata;", visibility="RuntimeInvisibleAnnotations", **kwargs):
    return fixture(attrs=[(visibility, annotations([(descriptor, pairs)]))], **kwargs)


def getstatic(pool, owner="com/termux/view/R$string", name="copy_text", descriptor="I"):
    return b"\xb2" + u2(pool.ref(owner, name, descriptor))


class ViewMetadataTests(unittest.TestCase):
    def test_semantic_pool_reordering_does_not_change_metadata(self):
        def build(reverse):
            names = ["Lexample/Metadata;", "mode", "value", "run", "x", "I", "answer", "java/io/IOException"]
            pairs = [("value", ("s", "release")), ("mode", ("I", 26))]
            return fixture(name="com/termux/view/Fixture$Inner", seed=lambda p: [p.utf(v) for v in names[::reverse]],
                fields=[{"name": "answer", "descriptor": "I", "access": 0x19, "constant": 42},
                        {"name": "list", "descriptor": "Ljava/util/List;", "signature": "Ljava/util/List<Ljava/lang/String;>;"}],
                methods=[method(descriptor="(I)V", parameters=[("x", 0x10)], exceptions=["java/io/IOException"],
                    attrs=[("RuntimeInvisibleAnnotations", annotations([("Lexample/Metadata;", pairs[::reverse])]))])],
                attrs=[("Signature", lambda p: u2(p.utf("Ljava/lang/Object;"))),
                       ("InnerClasses", lambda p: u2(1) + u2(p.cls("com/termux/view/Fixture$Inner")) +
                        u2(p.cls("com/termux/view/Fixture")) + u2(p.utf("Inner")) + u2(0x19)),
                       ("EnclosingMethod", lambda p: u2(p.cls("com/termux/view/Fixture")) + u2(p.nat("outer", "()V"))),
                       ("RuntimeVisibleAnnotations", annotations([("Lexample/ClassMetadata;", [("value", ("s", "visible"))])]))])
        before, after = build(1), build(-1)
        self.assertNotEqual(before, after)
        self.assertEqual(metadata.api_difference(before, after), {})
        json.dumps(metadata.accessible_api(before), sort_keys=True, allow_nan=False)

    def test_all_primitive_and_recursive_annotation_values_are_resolved(self):
        values = [("byte", ("B", -2)), ("char", ("C", 65535)), ("double", ("D", "8000000000000000")),
                  ("float", ("F", "7fc00001")), ("integer", ("I", -123)), ("long", ("J", 2 ** 55)),
                  ("short", ("S", -32768)), ("boolean", ("Z", 1)), ("string", ("s", "a\0\U0001f680")),
                  ("enum", ("e", ("Lexample/Mode;", "FULL"))), ("type", ("c", "[[I")),
                  ("voidType", ("c", "V")), ("nested", ("@", ("Lexample/Nested;", [("name", ("s", "n"))]))),
                  ("array", ("[", [("I", 1), ("s", "two")]))]
        result = metadata.accessible_api(annotated(values))["metadata"]["annotations"]["invisible"][0]["elements"]
        self.assertEqual(result["string"]["value"], "a\0\U0001f680")
        self.assertEqual(result["float"]["value"]["bits"], "7fc00001")
        self.assertEqual(result["double"]["value"]["bits"], "8000000000000000")
        self.assertEqual(result["long"]["value"]["value"], 2 ** 55)
        self.assertEqual(result["nested"]["value"]["elements"]["name"]["value"], "n")
        json.dumps(result, allow_nan=False)

    def test_annotation_tag_value_name_type_and_visibility_drift_detected(self):
        reference = annotated([("value", ("I", 26))])
        variants = [annotated([("value", ("I", 25))]), annotated([("value", ("J", 26))]),
                    annotated([("other", ("I", 26))]), annotated([("value", ("I", 26))], descriptor="Lexample/Other;"),
                    annotated([("value", ("I", 26))], visibility="RuntimeVisibleAnnotations")]
        for candidate in variants:
            with self.subTest(candidate=candidate[-20:]):
                self.assertIn("metadata", metadata.api_difference(reference, candidate))

    def test_annotation_pair_and_table_order_is_semantic_but_array_order_matters(self):
        first = ("Lexample/A;", [("z", ("I", 3)), ("a", ("s", "one"))])
        second = ("Lexample/B;", [])
        before = fixture(attrs=[("RuntimeVisibleAnnotations", annotations([first, second]))])
        after = fixture(attrs=[("RuntimeVisibleAnnotations", annotations([second, (first[0], first[1][::-1])]))])
        self.assertEqual(metadata.api_difference(before, after), {})
        a = annotated([("value", ("[", [("I", 1), ("I", 2)]))])
        b = annotated([("value", ("[", [("I", 2), ("I", 1)]))])
        self.assertIn("metadata", metadata.api_difference(a, b))

    def test_duplicate_annotation_elements_types_and_visibility_rejected(self):
        cases = [annotated([("value", ("I", 1)), ("value", ("I", 2))]),
                 fixture(attrs=[("RuntimeVisibleAnnotations", annotations([("Lexample/A;", []), ("Lexample/A;", [])]))]),
                 fixture(attrs=[("RuntimeVisibleAnnotations", annotations([("Lexample/A;", [])])),
                                ("RuntimeInvisibleAnnotations", annotations([("Lexample/A;", [])]))])]
        for raw in cases:
            with self.subTest(raw=raw[-20:]), self.assertRaisesRegex(ValueError, "duplicate annotation"):
                metadata.accessible_api(raw)

    def test_annotation_constant_types_and_ranges_rejected(self):
        def malformed(pool, tag, index):
            return u2(1) + u2(pool.utf("Lexample/A;")) + u2(1) + u2(pool.utf("value")) + tag + u2(index(pool))
        cases = [(b"s", lambda p: p.constant("Ljava/lang/String;", "wrong-tag")),
                 (b"I", lambda p: p.utf("not an integer")), (b"J", lambda p: p.integer(7)),
                 (b"c", lambda p: p.cls("java/lang/Object")), (b"Z", lambda p: p.integer(2)),
                 (b"B", lambda p: p.integer(128)), (b"C", lambda p: p.integer(-1)), (b"S", lambda p: p.integer(32768))]
        for tag, index in cases:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                metadata.accessible_api(fixture(attrs=[("RuntimeVisibleAnnotations", lambda p: malformed(p, tag, index))]))

    def test_annotation_malformed_truncated_trailing_and_invalid_descriptors_rejected(self):
        for raw in (b"", b"\0", b"\0\1", b"\0\0x"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                metadata.accessible_api(fixture(attrs=[("RuntimeInvisibleAnnotations", raw)]))
        for pairs, descriptor in [([("value", ("?", b""))], "Lexample/A;"), ([], "I"),
                                  ([("value", ("e", ("I", "A")))], "Lexample/A;"),
                                  ([("value", ("c", "[V"))], "Lexample/A;")]:
            with self.subTest(descriptor=descriptor), self.assertRaises(ValueError):
                metadata.accessible_api(annotated(pairs, descriptor=descriptor))

    def test_annotation_depth_node_and_table_limits_rejected(self):
        value = ("I", 1)
        for _ in range(metadata.MAX_ANNOTATION_DEPTH + 1):
            value = ("[", [value])
        with self.assertRaisesRegex(ValueError, "nesting depth"):
            metadata.accessible_api(annotated([("value", value)]))
        with patch.object(metadata, "MAX_ANNOTATION_NODES", 4):
            with self.assertRaisesRegex(ValueError, "node budget"):
                metadata.accessible_api(annotated([("value", ("[", [("I", 1)] * 3))]))
        with self.assertRaisesRegex(ValueError, "count budget"):
            metadata.accessible_api(fixture(attrs=[("RuntimeVisibleAnnotations", u2(metadata.MAX_ANNOTATION_ITEMS + 1))]))
        with self.assertRaisesRegex(ValueError, "element budget"):
            metadata.accessible_api(fixture(attrs=[("RuntimeVisibleAnnotations", lambda p: u2(1) +
                u2(p.utf("Lexample/A;")) + u2(metadata.MAX_ANNOTATION_ITEMS + 1))]))
        with self.assertRaisesRegex(ValueError, "array budget"):
            metadata.accessible_api(fixture(attrs=[("RuntimeVisibleAnnotations", lambda p: u2(1) +
                u2(p.utf("Lexample/A;")) + u2(1) + u2(p.utf("v")) + b"[" + u2(metadata.MAX_ANNOTATION_ITEMS + 1))]))

    def test_unsupported_metadata_fails_even_on_private_declarations(self):
        names = ["RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations", "RuntimeVisibleParameterAnnotations",
                 "RuntimeInvisibleParameterAnnotations", "AnnotationDefault", "FutureMetadata"]
        for name in names:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "unsupported"):
                metadata.accessible_api(fixture(methods=[method(access=2, attrs=[(name, b"\0\0")])]))
        with self.assertRaisesRegex(ValueError, "unsupported Code metadata"):
            metadata.accessible_api(fixture(methods=[method(code_attrs=[("RuntimeInvisibleTypeAnnotations", b"\0\0")])]))

    def test_all_nonprivate_visibilities_and_synthetic_flags_are_compared(self):
        rows = [{"name": name, "descriptor": "I", "access": flags} for name, flags in
                (("publicField", 1), ("protectedField", 4), ("packageField", 0), ("syntheticField", 0x1000), ("privateField", 2))]
        methods = [method(name=name, access=flags) for name, flags in
                   (("publicMethod", 1), ("protectedMethod", 4), ("packageMethod", 0), ("syntheticMethod", 0x1040), ("privateMethod", 2), ("<clinit>", 8))]
        api = metadata.accessible_api(fixture(fields=rows, methods=methods))
        self.assertEqual({r["name"] for r in api["fields"]}, {"publicField", "protectedField", "packageField", "syntheticField"})
        self.assertEqual({r["name"] for r in api["methods"]}, {"publicMethod", "protectedMethod", "packageMethod", "syntheticMethod"})
        self.assertIn("fields", metadata.api_difference(fixture(fields=rows), fixture(fields=[dict(r, access=2) if r["name"] == "packageField" else r for r in rows])))
        self.assertIn("methods", metadata.api_difference(fixture(methods=methods), fixture(methods=[dict(r, access=0) if r["name"] == "syntheticMethod" else r for r in methods])))

    def test_private_body_changes_are_not_api_equivalence_claims(self):
        before = fixture(methods=[method(code=b"\xb1"), method(name="hidden", access=2)])
        after = fixture(methods=[method(code=b"\0\xb1"), method(name="differentPrivateName", access=2)])
        self.assertNotEqual(before, after)
        self.assertEqual(metadata.api_difference(before, after), {})

    def test_field_constants_and_metadata_changes_detected(self):
        row = {"name": "MODE", "descriptor": "I", "access": 0x19, "constant": 2}
        self.assertIn("fields", metadata.api_difference(fixture(fields=[row]), fixture(fields=[dict(row, constant=3)])))
        string = {"name": "NAME", "descriptor": "Ljava/lang/String;", "access": 0x19, "constant": "name\0\U0001f680"}
        self.assertEqual(metadata.api_difference(fixture(fields=[string]), fixture(fields=[string], seed=lambda p: p.utf("pad"))), {})
        for marker in ("Synthetic", "Deprecated"):
            self.assertIn("fields", metadata.api_difference(fixture(fields=[row]), fixture(fields=[dict(row, attrs=[(marker, b"")])])))
            with self.assertRaisesRegex(ValueError, "nonempty marker"):
                metadata.accessible_api(fixture(fields=[dict(row, attrs=[(marker, b"x")])]))

    def test_generic_signature_exception_and_inner_metadata_drift_detected(self):
        row = method(exceptions=["java/io/IOException"], signature="()V")
        self.assertIn("methods", metadata.api_difference(fixture(methods=[row]), fixture(methods=[dict(row, exceptions=[])])))
        self.assertIn("methods", metadata.api_difference(fixture(methods=[row]), fixture(methods=[dict(row, signature="<T:Ljava/lang/Object;>()V")])))
        def inner(flags):
            return fixture(attrs=[("InnerClasses", lambda p: u2(1) + u2(p.cls("com/termux/view/Fixture")) +
                                  u2(p.cls("example/Outer")) + u2(p.utf("Fixture")) + u2(flags))])
        self.assertIn("innerClasses", metadata.api_difference(inner(1), inner(0x11)))
        self.assertIn("innerSelf", metadata.api_difference(inner(1), inner(0x11)))

    def test_method_parameters_are_ordered_and_do_not_infer_capture_type_names(self):
        row = method(name="<init>", descriptor="(Lexample/Outer;ZLandroid/view/MotionEvent;)V",
                     parameters=[("this$1", 0x8010), ("val$e2", 0x1010), ("val$mouseTrackingAtStartOfFling", 0x1010)])
        reference = fixture(methods=[row])
        parameters = metadata.accessible_api(reference)["methods"][0]["metadata"]["parameters"]
        self.assertEqual(parameters, [{"name": name, "access": flags} for name, flags in row["parameters"]])
        changed = dict(row, parameters=[row["parameters"][0], *row["parameters"][1:][::-1]])
        self.assertIn("methods", metadata.api_difference(reference, fixture(methods=[changed])))
        changed = dict(row, parameters=[("this$1", 0x1010), *row["parameters"][1:]])
        self.assertIn("methods", metadata.api_difference(reference, fixture(methods=[changed])))

    def test_method_parameters_absence_unnamed_bad_count_flags_types_and_length(self):
        row = method(descriptor="(I)V")
        named = fixture(methods=[dict(row, parameters=[("x", 0)])])
        unnamed = fixture(methods=[dict(row, parameters=[(None, 0)])])
        self.assertIn("methods", metadata.api_difference(named, unnamed))
        self.assertIn("methods", metadata.api_difference(unnamed, fixture(methods=[row])))
        cases = [lambda p: b"\0", lambda p: b"\1" + u2(p.utf("x")) + u2(1),
                 lambda p: b"\1" + u2(p.integer(3)) + u2(0), lambda p: b"\1\0", lambda p: b"\1\0\0\0\0x"]
        for raw in cases:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                metadata.accessible_api(fixture(methods=[dict(row, attrs=[("MethodParameters", raw)])]))

    def test_invalid_mutf8_and_constant_pool_types_rejected(self):
        for raw in (b"\0", b"\xc0\x81", b"\xe0\x80\x80", b"\xf0\x90\x80\x80", b"\xc2", b"\xe1\x41\x80"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                metadata.accessible_api(fixture(seed=lambda p: p.add(1, u2(len(raw)) + raw)))
        with self.assertRaisesRegex(ValueError, "type mismatch"):
            metadata.accessible_api(fixture(seed=lambda p: p.add(7, u2(p.integer(3)))))
        with self.assertRaisesRegex(ValueError, "type mismatch"):
            metadata.accessible_api(fixture(attrs=[("Signature", lambda p: u2(p.integer(1)))]))

    def test_version_size_duplicate_and_invalid_descriptor_fail_closed(self):
        for values in ({"major": 53}, {"minor": 1}):
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, "class version"):
                metadata.accessible_api(fixture(**values))
        with patch.object(metadata, "MAX_CLASS_BYTES", 1):
            with self.assertRaisesRegex(ValueError, "byte budget"):
                metadata.accessible_api(fixture())
        for raw in [fixture(methods=[method(), method()]), fixture(attrs=[("Deprecated", b""), ("Deprecated", b"")]),
                    fixture(fields=[{"name": "x", "descriptor": "[V"}]), fixture(methods=[method(descriptor="(I")]),
                    fixture(methods=[method(name="<init>", descriptor="()I")]), fixture(methods=[method(name="<clinit>", access=0)])]:
            with self.subTest(raw=raw[-15:]), self.assertRaises(ValueError):
                metadata.accessible_api(raw)


class ViewResourceBytecodeTests(unittest.TestCase):
    def test_actual_getstatic_multiplicity_private_constructors_and_class_initializer(self):
        def two(pool):
            return getstatic(pool) + b"\x57" + getstatic(pool) + b"\x57\xb1"
        rows = [method(name="hidden", access=2, code=two), method(name="<init>", code=two), method(name="<clinit>", access=8, code=two)]
        references = metadata.resource_field_references(fixture(methods=rows))
        self.assertEqual(len(references), 6)
        self.assertEqual([(r["method_name"], r["bytecode_offset"]) for r in references],
                         [(name, offset) for name in ("hidden", "<init>", "<clinit>") for offset in (0, 4)])
        self.assertTrue(all(r["owner"] == "com/termux/view/R$string" and r["field_name"] == "copy_text" and
                            r["field_descriptor"] == "I" and r["method_descriptor"] == "()V" for r in references))

    def test_stale_constant_pool_and_final_zero_do_not_fake_resource_use(self):
        seed = lambda p: p.ref("com/termux/view/R$string", "copy_text", "I")
        for code in (b"\xb1", b"\x03\x57\xb1"):
            with self.subTest(code=code):
                self.assertEqual(metadata.resource_field_references(fixture(seed=seed, methods=[method(code=code)])), [])

    def test_getfield_putstatic_and_operand_bytes_do_not_count(self):
        def code(pool):
            index = u2(pool.ref("com/termux/view/R$string", "copy_text", "I"))
            return b"\xb4" + index + b"\x57\x03\xb3" + index + b"\x10\xb2\x57\x11\0\xb2\x57\x84\0\xb2\xb1"
        self.assertEqual(metadata.resource_field_references(fixture(methods=[method(code=code)])), [])

    def test_wide_operands_and_switch_values_are_not_scanned_as_opcodes(self):
        table = b"\xaa\0\0\0" + struct.pack(">iiii", 20, 0xb2, 0xb2, 20) + b"\xb1"
        lookup = b"\xab\0\0\0" + struct.pack(">iiii", 20, 1, 0xb2, 20) + b"\xb1"
        for code in (b"\xc4\x84\0\xb2\0\xb2\xb1", b"\xc4\x15\0\xb2\x57\xb1", table, lookup):
            with self.subTest(code=code):
                self.assertEqual(metadata.resource_field_references(fixture(methods=[method(code=code)])), [])

    def test_getstatic_after_wide_and_switch_has_correct_offset(self):
        def code(pool):
            return b"\xc4\x84\0\xb2\0\xb2" + getstatic(pool) + b"\x57\xb1"
        result = metadata.resource_field_references(fixture(methods=[method(code=code)]))
        self.assertEqual(result[0]["bytecode_offset"], 6)
        def switch(pool):
            return b"\xaa\0\0\0" + struct.pack(">iiii", 20, 0, 0, 20) + getstatic(pool) + b"\x57\xb1"
        self.assertEqual(metadata.resource_field_references(fixture(methods=[method(code=switch)]))[0]["bytecode_offset"], 20)

    def test_invokedynamic_and_interface_invocation_decode_operands(self):
        def code(pool):
            dynamic = b"\xba" + u2(pool.indy()) + b"\0\0\x57"
            interface = b"\xb9" + u2(pool.ref("example/Interface", "accept", "(J)V", 11)) + b"\3\0"
            return dynamic + interface + getstatic(pool) + b"\x57\xb1"
        attrs = [("BootstrapMethods", lambda p: u2(1) + u2(p.handle()) + u2(0))]
        result = metadata.resource_field_references(fixture(methods=[method(code=code)], attrs=attrs))
        self.assertEqual(result[0]["bytecode_offset"], 11)

    def test_unknown_wrong_type_and_out_of_range_resource_operands_fail(self):
        cases = [lambda p: b"\xb2" + u2(p.utf("not Fieldref")) + b"\xb1",
                 lambda p: getstatic(p, descriptor="J") + b"\x58\xb1",
                 lambda p: getstatic(p, owner="com/termux/view/R$raw") + b"\x57\xb1",
                 lambda p: b"\xb2\xff\xff\xb1"]
        for code in cases:
            with self.subTest(code=code), self.assertRaises(ValueError):
                metadata.resource_field_references(fixture(methods=[method(code=code)]))
        self.assertEqual(metadata.resource_field_references(fixture(methods=[method(code=lambda p:
            getstatic(p, owner="android/R$string") + b"\x57\xb1")])), [])

    def test_reserved_truncated_wide_and_branch_operands_fail(self):
        cases = [b"\xca", b"\xfe", b"\xff", b"\xb2\0", b"\x11\0", b"\xc4\x10\0\1",
                 b"\xc4\x84\0\1\0", b"\xc4\x16\0\xff\xb1", b"\xa7\0\2\xb1", b"\xa7\xff\xff\xb1",
                 b"\xa7\0\4\xb1", b"\xbc\3\xb1", b"\x84\xff"]
        for code in cases:
            with self.subTest(code=code), self.assertRaises(ValueError):
                metadata.resource_field_references(fixture(methods=[method(code=code)]))

    def test_switch_padding_ranges_keys_and_targets_fail_closed(self):
        cases = [b"\xaa\1\0\0" + struct.pack(">iiii", 20, 0, 0, 20) + b"\xb1",
                 b"\xaa\0\0\0" + struct.pack(">iii", 0, 1, 0),
                 b"\xaa\0\0\0" + struct.pack(">iii", 0, 0, 100000),
                 b"\xab\0\0\0" + struct.pack(">ii", 0, -1),
                 b"\xab\0\0\0" + struct.pack(">iiiiii", 28, 2, 1, 28, 1, 28) + b"\xb1",
                 b"\xab\0\0\0" + struct.pack(">iiii", 1, 1, 0, 20) + b"\xb1"]
        for code in cases:
            with self.subTest(code=code), self.assertRaises(ValueError):
                metadata.resource_field_references(fixture(methods=[method(code=code)]))

    def test_invocation_reserved_bytes_counts_and_bootstrap_indices_fail(self):
        bootstrap = [("BootstrapMethods", lambda p: u2(1) + u2(p.handle()) + u2(0))]
        cases = [lambda p: b"\xba" + u2(p.indy()) + b"\0\1\x57\xb1",
                 lambda p: b"\xba" + u2(p.indy(1)) + b"\0\0\x57\xb1",
                 lambda p: b"\xba" + u2(p.ref("example/A", "run", "()V", 10)) + b"\0\0\xb1",
                 lambda p: b"\xb9" + u2(p.ref("example/A", "run", "(J)V", 11)) + b"\2\0\xb1",
                 lambda p: b"\xb9" + u2(p.ref("example/A", "run", "()V", 11)) + b"\1\1\xb1"]
        for code in cases:
            with self.subTest(code=code), self.assertRaises(ValueError):
                metadata.resource_field_references(fixture(methods=[method(code=code)], attrs=bootstrap))
        with self.assertRaisesRegex(ValueError, "bootstrap index"):
            metadata.accessible_api(fixture(seed=lambda p: p.indy()))

    def test_code_presence_lengths_parameters_and_handler_boundaries(self):
        cases = [method(no_code=True), method(code=b""), method(descriptor="(J)V", max_locals=2),
                 method(code=b"\x10\1\x57\xb1", handlers=[(0, 2, 1, 0)]),
                 method(code=b"\xb1", handlers=[(0, 0, 0, 0)]), method(code=b"\xb1", handlers=[(0, 2, 0, 0)]),
                 method(access=0x101, attrs=[("Code", lambda p: code_payload(p, b"\xb1"))])]
        for row in cases:
            with self.subTest(row=row), self.assertRaises(ValueError):
                metadata.accessible_api(fixture(methods=[row]))
        metadata.accessible_api(fixture(methods=[method(code=b"\x10\1\x57\xb1", handlers=[(0, 4, 3, 0)])]))

    def test_debug_and_stack_map_structures_are_validated_not_compared(self):
        attrs = [("LineNumberTable", u2(1) + u2(0) + u2(10)),
                 ("LocalVariableTable", lambda p: u2(1) + u2(0) + u2(1) + u2(p.utf("this")) +
                  u2(p.utf("Lcom/termux/view/Fixture;")) + u2(0)),
                 ("StackMapTable", u2(1) + b"\0")]
        self.assertEqual(metadata.api_difference(fixture(methods=[method()]), fixture(methods=[method(code_attrs=attrs)])), {})
        for name, data in [("LineNumberTable", u2(1) + u2(1) + u2(10)), ("StackMapTable", u2(1) + b"\x80"),
                           ("StackMapTable", u2(1) + b"\x40\x09"), ("StackMapTable", u2(1) + b"\x40\x08\0\0"),
                           ("StackMapTable", u2(1) + b"\x01"), ("LineNumberTable", u2(0) + b"x")]:
            with self.subTest(name=name, data=data), self.assertRaises(ValueError):
                metadata.accessible_api(fixture(methods=[method(code_attrs=[(name, data)])]))

    def test_offsets_are_diagnostic_and_source_api_does_not_prove_byte_equivalence(self):
        before = fixture(methods=[method(code=lambda p: getstatic(p) + b"\x57\xb1")])
        after = fixture(methods=[method(code=lambda p: b"\0" + getstatic(p) + b"\x57\xb1")])
        self.assertEqual(metadata.api_difference(before, after), {})
        old, new = metadata.resource_field_references(before), metadata.resource_field_references(after)
        self.assertNotEqual(old, new)
        self.assertEqual([{k: v for k, v in row.items() if k != "bytecode_offset"} for row in old],
                         [{k: v for k, v in row.items() if k != "bytecode_offset"} for row in new])


class PinnedViewMetadataTests(unittest.TestCase):
    def test_all_nineteen_pinned_view_classes_parse_as_data(self):
        raw = (TOOLS.parent / "app/libs/terminal-view-v0.118.0.aar").read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), "06b3e83cf34c40e82ba5348a88fc72657ebf35177e28f567e86b2389c34cd2d0")
        jar = java_archive.archive_entries(raw)["classes.jar"]
        self.assertEqual(hashlib.sha256(jar).hexdigest(), "9bceb16dc4d35f412aff579a11fcf4498bd5935dbbee85b9adb11c4ea3ec9068")
        classes = {name: data for name, data in java_archive.archive_entries(jar).items() if name.endswith(".class")}
        self.assertEqual(len(classes), 19)
        parameters, parameter_entries, uses = 0, 0, []
        for name, data in sorted(classes.items()):
            with self.subTest(name=name):
                api = metadata.accessible_api(data)
                self.assertEqual(api["name"] + ".class", name)
                json.dumps(api, sort_keys=True, allow_nan=False)
                uses.extend(metadata.resource_field_references(data))
                for row in java_archive.parse_class(data)["methods"]:
                    if "MethodParameters" in row["attributes"]:
                        parameters += 1
                        parameter_entries += row["attributes"]["MethodParameters"][0]
        self.assertEqual((parameters, parameter_entries), (119, 218))
        self.assertEqual({(row["owner"], row["field_name"]) for row in uses}, {
            ("com/termux/view/R$drawable", "text_select_handle_left_material"),
            ("com/termux/view/R$drawable", "text_select_handle_right_material"),
            ("com/termux/view/R$string", "copy_text"), ("com/termux/view/R$string", "paste_text"),
            ("com/termux/view/R$string", "text_selection_more")})
        self.assertTrue(all(row["field_descriptor"] == "I" for row in uses))


if __name__ == "__main__":
    unittest.main()
