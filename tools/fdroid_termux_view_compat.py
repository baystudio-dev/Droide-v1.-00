"""Proof-only compatibility for two exact source-built View class preimages.

No reference AAR, file I/O, compiler, JVM, or network input is used here. This is
not a general classfile writer or a relaxed metadata comparison. Public input
hashes freeze the entire raw candidate, and the existing strict inspection is
required before and after the small reviewed attribute edits.

Preservation evidence compares the adapted class with its raw source-built
candidate. It does not claim historical Code-byte equivalence, Android runtime
equivalence, production integration, or F-Droid acceptance.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
from types import MappingProxyType

import fdroid_termux_view_metadata as metadata
import java_archive


@dataclass(frozen=True)
class _Rule:
    class_path: str
    raw_sha256: str
    descriptor: str
    modern: tuple[tuple[str, int], ...]
    historical: tuple[tuple[str, int], ...]
    removed_signature: str | None
    appendable_utf8: tuple[str, ...] = ()
    constructor_access: int = 0


_RULES = MappingProxyType({rule.class_path: rule for rule in (
    _Rule("com/termux/view/TerminalView$1$1.class",
          "fdfc286756a17ceceff219c34373251bb28074f545aac6c9991d7ee60ff4e869",
          "(Lcom/termux/view/TerminalView$1;ZLandroid/view/MotionEvent;)V",
          (("this$1", 32784), ("val$mouseTrackingAtStartOfFling", 4112), ("val$e2", 4112)),
          (("this$1", 32784), ("val$e2", 4112), ("val$mouseTrackingAtStartOfFling", 4112)), "()V"),
    _Rule("com/termux/view/TerminalView$2.class",
          "e3acbbd955ae645baa2ca28e2de58ad92cbdc8e7dab93f73973a2175eba28081",
          "(Lcom/termux/view/TerminalView;Landroid/view/View;Z)V",
          (("this$0", 32784), ("targetView", 0), ("fullEditor", 0)),
          (("this$0", 32784), ("x0", 0), ("x1", 0)), None, ("x0", "x1")),
)})
REVIEWED_CLASSES = tuple(_RULES)


def _require(condition, message):
    if not condition:
        raise ValueError("View compatibility: " + message)


def _u2(value):
    _require(0 <= value <= 65535, "u2/constant-pool overflow")
    return value.to_bytes(2, "big")


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


@dataclass(frozen=True)
class _Attribute:
    name: str
    name_index: int
    data: bytes
    raw: bytes


@dataclass(frozen=True)
class _Member:
    name: str
    descriptor: str
    access: int
    header: bytes
    attributes_start: int
    end: int
    attributes: tuple[_Attribute, ...]
    raw: bytes


@dataclass(frozen=True)
class _Frame:
    raw: bytes
    name: str
    pool: metadata.Pool
    pool_count: int
    pool_bytes: bytes
    pool_end: int
    class_header: bytes
    fields: tuple[_Member, ...]
    methods: tuple[_Member, ...]
    class_attributes: bytes


def _frame(raw):
    # Mandatory first full validation, including private metadata and all Code.
    metadata.accessible_api(raw)
    parsed = java_archive.parse_class(raw)
    count = int.from_bytes(raw[8:10], "big")
    pool = metadata.Pool(parsed["constantPool"], count)
    r = metadata.Reader(raw)
    pool_end = 10 + len(parsed["constantPool"])
    r.take(pool_end)

    def attributes():
        rows = []
        for _ in range(r.u2()):
            start, index = r.pos, r.u2()
            data = r.take(r.u4())
            rows.append(_Attribute(pool.utf(index), index, data, raw[start:r.pos]))
        _require(len({row.name for row in rows}) == len(rows), "duplicate attribute")
        return tuple(rows)

    def members():
        rows = []
        for _ in range(r.u2()):
            start = r.pos
            access, name, descriptor = r.u2(), pool.utf(r.u2()), pool.utf(r.u2())
            attrs_start = r.pos
            attrs = attributes()
            rows.append(_Member(name, descriptor, access, raw[start:attrs_start], attrs_start,
                                r.pos, attrs, raw[start:r.pos]))
        return tuple(rows)

    start = r.pos
    r.take(6)
    r.take(2 * r.u2())
    header = raw[start:r.pos]
    fields, methods = members(), members()
    start = r.pos
    attributes()
    r.end()
    return _Frame(raw, parsed["name"], pool, count, parsed["constantPool"], pool_end,
                  header, fields, methods, raw[start:r.pos])


def _parameters(attribute, pool):
    r = metadata.Reader(attribute.data)
    result = tuple((pool.utf(r.u2()), r.u2()) for _ in range(r.u1()))
    r.end()
    return result


def _constructor(frame, rule, adapted=False):
    _require(any(rule is known for known in _RULES.values()), "unreviewed compatibility rule")
    _require(frame.name + ".class" == rule.class_path, "wrong class identity")
    rows = [row for row in frame.methods if row.name == "<init>"]
    _require(len(rows) == 1, "missing or overloaded constructor")
    row = rows[0]
    _require(row.descriptor == rule.descriptor and row.access == rule.constructor_access,
             "unexpected constructor descriptor/access flags")
    attrs = {value.name: value for value in row.attributes}
    has_signature = rule.removed_signature is not None and not adapted
    expected_attrs = {"Code", "MethodParameters"} | ({"Signature"} if has_signature else set())
    _require(set(attrs) == expected_attrs, "unexpected/missing constructor attributes or already adapted")
    expected = rule.historical if adapted else rule.modern
    _require(_parameters(attrs["MethodParameters"], frame.pool) == expected,
             "unexpected MethodParameters names/order/count/flags or already adapted")
    if has_signature:
        index = metadata._index(attrs["Signature"].data)
        _require(frame.pool.utf(index) == rule.removed_signature, "unexpected constructor Signature")
    return row


def _utf8_indices(frame):
    result = {}
    for index, entry in enumerate(frame.pool.entries):
        if entry is not None and entry[0] == 1:
            result.setdefault(entry[1], index)
    return result


def _utf8_entry(name):
    _require(name in {"x0", "x1"}, "unreviewed appended constant")
    raw = name.encode("ascii")
    return b"\x01" + _u2(len(raw)) + raw


def _certify_adaptation(before, after, rule):
    """Independently reframe both byte strings and certify the exact envelope.

    This never trusts rewrite offsets, a writer-supplied delta list, or parsed
    API equality alone. Entire unrelated raw records and Code attributes are
    compared, including their ordering, indices, lengths and debug/stack data.
    """
    old, new = _frame(before), _frame(after)
    old_ctor, new_ctor = _constructor(old, rule), _constructor(new, rule, adapted=True)
    old_utf8 = _utf8_indices(old)
    additions = tuple(name for name in rule.appendable_utf8 if name not in old_utf8)
    expected_suffix = b"".join(_utf8_entry(name) for name in additions)
    _require(new.pool_count == old.pool_count + len(additions) <= 65535 and
             new.pool_bytes == old.pool_bytes + expected_suffix,
             "old constant pool changed or unexpected appended entries")
    _require(before[:8] == after[:8] and old.class_header == new.class_header and
             tuple(row.raw for row in old.fields) == tuple(row.raw for row in new.fields) and
             old.class_attributes == new.class_attributes and len(old.methods) == len(new.methods),
             "unrelated class/field structure changed")
    for left, right in zip(old.methods, new.methods):
        _require(left.header == right.header, "method declaration/header changed")
        old_code = tuple(attr.raw for attr in left.attributes if attr.name == "Code")
        new_code = tuple(attr.raw for attr in right.attributes if attr.name == "Code")
        _require(old_code == new_code, "Code attribute changed relative to raw candidate")
        if left is not old_ctor:
            _require(left.raw == right.raw, "unrelated method changed")
            continue
        _require(right is new_ctor, "constructor order changed")
        kept = tuple(attr for attr in left.attributes if not (attr.name == "Signature" and rule.removed_signature))
        _require(tuple(attr.name for attr in kept) == tuple(attr.name for attr in right.attributes),
                 "constructor attribute order/count changed outside reviewed removal")
        for old_attr, new_attr in zip(kept, right.attributes):
            if old_attr.name == "MethodParameters":
                _require(old_attr.raw[:6] == new_attr.raw[:6], "MethodParameters name index/length changed")
                # Payload semantics, order, flags and count were checked above.
            else:
                _require(old_attr.raw == new_attr.raw, "unrelated constructor attribute changed")
    return {"existingConstantPoolPreserved": True, "allCodeAttributesPreserved": True,
            "unrelatedClassStructurePreserved": True, "appendedUtf8Constants": list(additions)}


def _adapt_reviewed(raw, rule):
    """Pure fixture-testable transformation for the immutable reviewed rules.

    Public proof callers must use adapt_class, which additionally pins the entire
    preimage. This internal entry point accepts no caller-defined edit rules.
    """
    frame = _frame(raw)
    constructor = _constructor(frame, rule)
    indices, appended, additions = _utf8_indices(frame), bytearray(), []
    for name in rule.appendable_utf8:
        if name not in indices:
            indices[name] = frame.pool_count + len(additions)
            appended.extend(_utf8_entry(name))
            additions.append(name)
    new_count = frame.pool_count + len(additions)
    _require(new_count <= 65535, "constant-pool overflow")
    _require(all(name in indices for name, _ in rule.historical), "historical parameter constant is absent")
    parameters = bytes((len(rule.historical),)) + b"".join(
        _u2(indices[name]) + _u2(flags) for name, flags in rule.historical)
    attrs = []
    for attr in constructor.attributes:
        if attr.name == "Signature" and rule.removed_signature is not None:
            continue
        attrs.append(attr.raw[:6] + parameters if attr.name == "MethodParameters" else attr.raw)
    replacement = _u2(len(attrs)) + b"".join(attrs)
    body = (raw[frame.pool_end:constructor.attributes_start] + replacement + raw[constructor.end:])
    adapted = raw[:8] + _u2(new_count) + frame.pool_bytes + bytes(appended) + body
    certificate = _certify_adaptation(raw, adapted, rule)
    changes = [{"path": "methods/<init>" + rule.descriptor + "/MethodParameters",
                "before": [{"name": name, "access": flags} for name, flags in rule.modern],
                "after": [{"name": name, "access": flags} for name, flags in rule.historical]}]
    if rule.removed_signature is not None:
        changes.append({"path": "methods/<init>" + rule.descriptor + "/Signature",
                        "before": rule.removed_signature, "after": None})
    evidence = {"rawSha256": _sha(raw), "adaptedSha256": _sha(adapted), "class": rule.class_path,
                "descriptor": rule.descriptor, "changedAttributes": changes, **certificate,
                "proofOnly": True, "referenceBinaryUsed": False, "productionArtifactUpdated": False,
                "preservationScope": "raw source-built candidate, not historical Code or runtime equivalence"}
    return adapted, evidence


def adapt_class(class_path, raw):
    """Adapt exactly one frozen raw CI candidate; every other preimage rejects."""
    _require(isinstance(class_path, str) and class_path in _RULES, "unknown class identity")
    rule = _RULES[class_path]
    frame = _frame(raw)
    _require(frame.name + ".class" == class_path, "wrong class identity")
    _require(_sha(raw) == rule.raw_sha256, "raw candidate SHA-256 changed or already adapted")
    return _adapt_reviewed(raw, rule)
