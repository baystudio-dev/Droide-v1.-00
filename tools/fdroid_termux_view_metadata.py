"""Bounded, parse-only Java 8 View metadata and resource-reference inspection.

No class is loaded, linked, verified by a JVM, or executed. API comparison
includes all non-private declarations (also package-private/synthetic), resolved
declaration annotations, MethodParameters, constants, signatures, exceptions,
and inner/enclosing metadata. Debug data and method implementations are not API
equivalence evidence; complete class hashes must be reported separately.

The bytecode decoder checks instruction framing, operands, constant-pool types,
branch/handler boundaries and known nested Code attributes. It is NOT a JVM
stack/type verifier or a proof that an instruction executes at runtime.
Resource evidence is actual GETSTATIC instructions, not unused pool entries.

Scope: class version 52.0 and the explicitly supported attributes below.
Unknown attributes and unsupported annotation variants fail closed. Format
references: JVMS 8 chapters 4 and 6 (docs.oracle.com/javase/specs/jvms/se8/).
"""

from __future__ import annotations

import java_archive


MAX_CLASS_BYTES = 4 * 1024 * 1024
MAX_ANNOTATION_DEPTH = 32
MAX_ANNOTATION_NODES = 10000
MAX_ANNOTATION_ITEMS = 4096
RESOURCE_OWNERS = frozenset(("com/termux/view/R$drawable", "com/termux/view/R$string"))
ANNOTATIONS = {"RuntimeVisibleAnnotations": "visible", "RuntimeInvisibleAnnotations": "invisible"}
COMMON_ATTRIBUTES = {"Signature", "Synthetic", "Deprecated", *ANNOTATIONS}


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Reader:
    def __init__(self, raw):
        self.raw, self.pos = raw, 0

    def take(self, size):
        require(size >= 0 and self.pos + size <= len(self.raw), "truncated class metadata")
        result = self.raw[self.pos:self.pos + size]
        self.pos += size
        return result

    def number(self, size, signed=False):
        return int.from_bytes(self.take(size), "big", signed=signed)

    def u1(self):
        return self.number(1)

    def u2(self):
        return self.number(2)

    def u4(self):
        return self.number(4)

    def end(self):
        require(self.pos == len(self.raw), "trailing class metadata bytes")


def _mutf8(raw):
    """Decode JVM modified UTF-8, including NUL and UTF-16 surrogate pairs."""
    r, units = Reader(raw), bytearray()
    while r.pos < len(raw):
        first = r.u1()
        if 1 <= first <= 0x7f:
            value = first
        elif 0xc0 <= first <= 0xdf:
            second = r.u1()
            require(second & 0xc0 == 0x80, "invalid modified UTF-8 continuation")
            value = ((first & 31) << 6) | (second & 63)
            require(value >= 0x80 or (first == 0xc0 and second == 0x80), "overlong modified UTF-8")
        elif 0xe0 <= first <= 0xef:
            second, third = r.u1(), r.u1()
            require((second & 0xc0) == 0x80 and (third & 0xc0) == 0x80, "invalid modified UTF-8 continuation")
            value = ((first & 15) << 12) | ((second & 63) << 6) | (third & 63)
            require(value >= 0x800, "overlong modified UTF-8")
        else:
            raise ValueError("invalid modified UTF-8 leading byte")
        units.extend(value.to_bytes(2, "big"))
    return bytes(units).decode("utf-16-be", errors="surrogatepass")


def _name(value, method=False):
    require(value and not any(c in value for c in ".;[/\x00"), "invalid unqualified metadata name")
    require(not any(c in value for c in "<>") or (method and value in {"<init>", "<clinit>"}), "invalid special method name")
    return value


def _binary_name(value):
    require(value and not value.startswith("["), "expected binary class name")
    for part in value.split("/"):
        _name(part)
    return value


def _type_at(value, pos=0, void=False):
    require(pos < len(value), "truncated descriptor")
    start = pos
    while pos < len(value) and value[pos] == "[":
        pos += 1
    require(pos - start <= 255 and pos < len(value), "invalid array descriptor")
    kind = value[pos]
    if kind in "BCDFIJSZ" or (kind == "V" and void and pos == start):
        return pos + 1
    require(kind == "L", "invalid descriptor kind")
    finish = value.find(";", pos)
    require(finish != -1, "unterminated object descriptor")
    _binary_name(value[pos + 1:finish])
    return finish + 1


def _field_descriptor(value, void=False):
    require(_type_at(value, void=void) == len(value), "trailing field descriptor")
    return value


def _method_descriptor(value):
    require(value.startswith("("), "expected method descriptor")
    pos, args = 1, []
    while pos < len(value) and value[pos] != ")":
        finish = _type_at(value, pos)
        args.append(value[pos:finish])
        pos = finish
    require(pos < len(value) and value[pos] == ")", "unterminated method descriptor")
    result = value[pos + 1:]
    _field_descriptor(result, void=True)
    require(sum(_slots(arg) for arg in args) <= 255, "method parameter slot budget exceeded")
    return args, result


def _slots(descriptor):
    return 2 if descriptor in {"J", "D"} else 1


class Pool:
    def __init__(self, raw, count):
        require(count > 0, "empty constant pool")
        self.entries = [None] * count
        r, index = Reader(raw), 1
        while index < count:
            tag = r.u1()
            if tag == 1:
                value = _mutf8(r.take(r.u2()))
            elif tag in {3, 4, 5, 6}:
                value = r.take(8 if tag in {5, 6} else 4)
            elif tag in {7, 8, 16}:
                value = r.u2()
            elif tag in {9, 10, 11, 12, 18}:
                value = (r.u2(), r.u2())
            elif tag == 15:
                value = (r.u1(), r.u2())
            else:
                raise ValueError("unsupported Java 8 constant-pool tag")
            self.entries[index] = (tag, value)
            index += 2 if tag in {5, 6} else 1
        require(index == count, "long/double constant exceeds pool slots")
        r.end()
        for index, entry in enumerate(self.entries):
            if entry is None:
                continue
            tag, value = entry
            if tag == 7:
                self.cls(index)
            elif tag == 8:
                self.utf(value)
            elif tag == 12:
                name, descriptor = self.name_type(index)
                _name(name, method=descriptor.startswith("("))
                if descriptor.startswith("("):
                    _method_descriptor(descriptor)
                else:
                    _field_descriptor(descriptor)
            elif tag in {9, 10, 11}:
                self.reference(index, {tag})
            elif tag == 15:
                self.handle(index)
            elif tag == 16:
                _method_descriptor(self.utf(value))
            elif tag == 18:
                name, descriptor = self.name_type(value[1])
                _name(name)
                _method_descriptor(descriptor)

    def get(self, index, tags):
        require(0 < index < len(self.entries) and self.entries[index] is not None, "invalid constant-pool index")
        tag, value = self.entries[index]
        require(tag in tags, "constant-pool type mismatch")
        return value

    def utf(self, index):
        return self.get(index, {1})

    def cls(self, index, optional=False, binary=False):
        if optional and index == 0:
            return None
        value = self.utf(self.get(index, {7}))
        if value.startswith("[") and not binary:
            return _field_descriptor(value)
        return _binary_name(value)

    def name_type(self, index):
        name, descriptor = self.get(index, {12})
        return self.utf(name), self.utf(descriptor)

    def reference(self, index, tags):
        owner, nat = self.get(index, tags)
        name, descriptor = self.name_type(nat)
        tag = self.entries[index][0]
        _name(name, method=tag != 9)
        if tag == 9:
            _field_descriptor(descriptor)
        else:
            _, result = _method_descriptor(descriptor)
            require(name != "<clinit>" and (name != "<init>" or (tag == 10 and result == "V")), "invalid method reference name")
        return self.cls(owner), name, descriptor

    def handle(self, index):
        kind, ref = self.get(index, {15})
        require(1 <= kind <= 9, "invalid method-handle kind")
        tags = {9} if kind <= 4 else {10, 11} if kind in {6, 7} else {11} if kind == 9 else {10}
        owner, name, descriptor = self.reference(ref, tags)
        require((name == "<init>") == (kind == 8), "invalid method-handle constructor")
        return kind, owner, name, descriptor


def _index(raw):
    r = Reader(raw)
    result = r.u2()
    r.end()
    return result


def _typed_constant(pool, index, descriptor):
    tags = {"B": 3, "C": 3, "I": 3, "S": 3, "Z": 3, "J": 5, "F": 4, "D": 6, "Ljava/lang/String;": 8}
    require(descriptor in tags, "unsupported ConstantValue descriptor")
    value = pool.get(index, {tags[descriptor]})
    if descriptor == "Ljava/lang/String;":
        return {"kind": descriptor, "value": pool.utf(value)}
    if descriptor in {"F", "D"}:
        # Bit-exact numeric values preserve negative zero and NaN payloads and
        # stay canonical/JSON-safe without Python NaN equality ambiguity.
        return {"kind": descriptor, "bits": value.hex()}
    value = int.from_bytes(value, "big", signed=True)
    bounds = {"B": (-128, 127), "C": (0, 65535), "S": (-32768, 32767), "Z": (0, 1)}
    if descriptor in bounds:
        low, high = bounds[descriptor]
        require(low <= value <= high, "constant outside declared primitive range")
    return {"kind": descriptor, "value": value}


class AnnotationBudget:
    def __init__(self):
        self.remaining = MAX_ANNOTATION_NODES

    def use(self, depth):
        require(depth <= MAX_ANNOTATION_DEPTH, "annotation nesting depth exceeded")
        self.remaining -= 1
        require(self.remaining >= 0, "annotation node budget exceeded")


def _annotation(r, pool, budget, depth):
    budget.use(depth)
    descriptor = pool.utf(r.u2())
    _field_descriptor(descriptor)
    require(descriptor.startswith("L"), "annotation type must be an object descriptor")
    count = r.u2()
    require(count <= MAX_ANNOTATION_ITEMS, "annotation element budget exceeded")
    elements = {}
    for _ in range(count):
        name = _name(pool.utf(r.u2()))
        require(name not in elements, "duplicate annotation element name")
        elements[name] = _element(r, pool, budget, depth + 1)
    return {"type": descriptor, "elements": {name: elements[name] for name in sorted(elements)}}


def _element(r, pool, budget, depth):
    budget.use(depth)
    tag = chr(r.u1())
    if tag in "BCDFIJSZ":
        value = _typed_constant(pool, r.u2(), tag)
    elif tag == "s":
        value = pool.utf(r.u2())
    elif tag == "e":
        descriptor, name = pool.utf(r.u2()), _name(pool.utf(r.u2()))
        _field_descriptor(descriptor)
        require(descriptor.startswith("L"), "enum type must be an object descriptor")
        value = {"type": descriptor, "name": name}
    elif tag == "c":
        value = _field_descriptor(pool.utf(r.u2()), void=True)
    elif tag == "@":
        value = _annotation(r, pool, budget, depth + 1)
    elif tag == "[":
        count = r.u2()
        require(count <= MAX_ANNOTATION_ITEMS, "annotation array budget exceeded")
        value = [_element(r, pool, budget, depth + 1) for _ in range(count)]
    else:
        raise ValueError("unsupported annotation element tag")
    return {"tag": tag, "value": value}


def _annotation_attribute(raw, pool, budget):
    r, entries = Reader(raw), {}
    count = r.u2()
    require(count <= MAX_ANNOTATION_ITEMS, "annotation count budget exceeded")
    for _ in range(count):
        item = _annotation(r, pool, budget, 0)
        require(item["type"] not in entries, "duplicate annotation type")
        entries[item["type"]] = item
    r.end()
    return [entries[name] for name in sorted(entries)]


def _metadata(attrs, pool, budget, location, descriptor=None):
    extras = {"class": {"SourceFile", "SourceDebugExtension", "InnerClasses", "EnclosingMethod", "BootstrapMethods"},
              "field": {"ConstantValue"}, "method": {"Code", "Exceptions", "MethodParameters"}}
    unknown = set(attrs) - COMMON_ATTRIBUTES - extras[location]
    require(not unknown, "unsupported " + location + " metadata: " + ", ".join(sorted(unknown)))
    for marker in ("Synthetic", "Deprecated"):
        require(marker not in attrs or not attrs[marker], "nonempty marker attribute")
    annotations = {visibility: _annotation_attribute(attrs[name], pool, budget) if name in attrs else [] for name, visibility in ANNOTATIONS.items()}
    require(not ({a["type"] for a in annotations["visible"]} & {a["type"] for a in annotations["invisible"]}), "duplicate annotation across visibility tables")
    result = {"signature": pool.utf(_index(attrs["Signature"])) if "Signature" in attrs else None,
              "synthetic": "Synthetic" in attrs, "deprecated": "Deprecated" in attrs, "annotations": annotations}
    if location == "method":
        exceptions = []
        if "Exceptions" in attrs:
            r = Reader(attrs["Exceptions"])
            exceptions = [pool.cls(r.u2(), binary=True) for _ in range(r.u2())]
            r.end()
            require(len(exceptions) == len(set(exceptions)), "duplicate declared exception")
        result["exceptions"] = sorted(exceptions)
        result["parameters"] = None
        if "MethodParameters" in attrs:
            r = Reader(attrs["MethodParameters"])
            count = r.u1()
            require(count == len(_method_descriptor(descriptor)[0]), "MethodParameters count differs from descriptor")
            parameters = []
            for _ in range(count):
                name, flags = r.u2(), r.u2()
                require(flags & ~0x9010 == 0, "invalid MethodParameters flags")
                parameters.append({"name": _name(pool.utf(name)) if name else None, "access": flags})
            r.end()
            result["parameters"] = parameters
    return result


def _bootstrap(attrs, pool):
    count = 0
    if "BootstrapMethods" in attrs:
        r = Reader(attrs["BootstrapMethods"])
        count = r.u2()
        for _ in range(count):
            kind, *_ = pool.handle(r.u2())
            require(kind in {6, 8}, "invalid bootstrap method handle kind")
            for _ in range(r.u2()):
                pool.get(r.u2(), {3, 4, 5, 6, 7, 8, 15, 16})
        r.end()
    for entry in pool.entries:
        if entry is not None and entry[0] == 18:
            require(entry[1][0] < count, "invalid invokedynamic bootstrap index")


def _inner_enclosing(attrs, pool):
    inner, enclosing = [], None
    if "InnerClasses" in attrs:
        r, names = Reader(attrs["InnerClasses"]), set()
        for _ in range(r.u2()):
            name, outer, simple, flags = r.u2(), r.u2(), r.u2(), r.u2()
            name = pool.cls(name, binary=True)
            require(name not in names, "duplicate inner class metadata")
            require(flags & ~0x761f == 0, "invalid inner class flags")
            names.add(name)
            inner.append({"inner": name, "outer": pool.cls(outer, optional=True, binary=True),
                          "name": _name(pool.utf(simple)) if simple else None, "access": flags})
        r.end()
    if "EnclosingMethod" in attrs:
        r = Reader(attrs["EnclosingMethod"])
        owner, method = pool.cls(r.u2(), binary=True), r.u2()
        enclosing = {"class": owner, "method": None}
        if method:
            name, descriptor = pool.name_type(method)
            _name(name, method=True)
            _method_descriptor(descriptor)
            enclosing["method"] = {"name": name, "descriptor": descriptor}
        r.end()
    return sorted(inner, key=lambda item: item["inner"]), enclosing


_WIDTHS = [1] * 0xca
for _op in (0x10, 0x12, *range(0x15, 0x1a), *range(0x36, 0x3b), 0xa9, 0xbc):
    _WIDTHS[_op] = 2
for _op in (0x11, 0x13, 0x14, 0x84, *range(0x99, 0xa9), *range(0xb2, 0xb9), 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7):
    _WIDTHS[_op] = 3
_WIDTHS[0xc5] = 4
for _op in (0xb9, 0xba, 0xc8, 0xc9):
    _WIDTHS[_op] = 5


def _instructions(code, pool, max_locals):
    r, starts, targets, references = Reader(code), {}, [], []
    def local(index, width=1):
        require(index + width <= max_locals, "bytecode local index out of bounds")
    while r.pos < len(code):
        pc, opcode = r.pos, r.u1()
        require(opcode < len(_WIDTHS), "unknown or reserved bytecode opcode")
        starts[pc] = opcode
        if opcode in {0xaa, 0xab}:
            require(not any(r.take((-r.pos) % 4)), "nonzero switch padding")
            targets.append(pc + r.number(4, signed=True))
            if opcode == 0xaa:
                low, high = r.number(4, signed=True), r.number(4, signed=True)
                require(low <= high and high - low + 1 <= (len(code) - r.pos) // 4, "invalid tableswitch range")
                targets.extend(pc + r.number(4, signed=True) for _ in range(high - low + 1))
            else:
                count = r.number(4, signed=True)
                require(0 <= count <= (len(code) - r.pos) // 8, "invalid lookupswitch count")
                previous = None
                for _ in range(count):
                    key, target = r.number(4, signed=True), r.number(4, signed=True)
                    require(previous is None or previous < key, "unordered or duplicate lookupswitch key")
                    previous = key
                    targets.append(pc + target)
            continue
        if opcode == 0xc4:
            modified = r.u1()
            require(modified in {*range(0x15, 0x1a), *range(0x36, 0x3b), 0xa9, 0x84}, "invalid wide opcode")
            local(r.u2(), 2 if modified in {0x16, 0x18, 0x37, 0x39} else 1)
            if modified == 0x84:
                r.take(2)
            continue
        operands = r.take(_WIDTHS[opcode] - 1)
        index = int.from_bytes(operands[:2], "big") if len(operands) >= 2 else operands[0] if operands else None
        if opcode in {0x12, 0x13, 0x14}:
            pool.get(index, {5, 6} if opcode == 0x14 else {3, 4, 7, 8, 15, 16})
        elif opcode in {*range(0x15, 0x1a), *range(0x36, 0x3b), 0xa9}:
            local(index, 2 if opcode in {0x16, 0x18, 0x37, 0x39} else 1)
        elif 0x1a <= opcode <= 0x2d:
            local((opcode - 0x1a) % 4, 2 if (opcode - 0x1a) // 4 in {1, 3} else 1)
        elif 0x3b <= opcode <= 0x4e:
            local((opcode - 0x3b) % 4, 2 if (opcode - 0x3b) // 4 in {1, 3} else 1)
        elif opcode == 0x84:
            local(operands[0])
        elif 0x99 <= opcode <= 0xa8 or opcode in {0xc6, 0xc7, 0xc8, 0xc9}:
            targets.append(pc + int.from_bytes(operands, "big", signed=True))
        elif 0xb2 <= opcode <= 0xb5:
            owner, name, descriptor = pool.reference(index, {9})
            if opcode == 0xb2 and owner.startswith("com/termux/view/R$"):
                require(owner in RESOURCE_OWNERS, "unsupported View resource owner")
            if opcode == 0xb2 and owner in RESOURCE_OWNERS:
                require(descriptor == "I", "View resource field is not an int")
                references.append({"bytecode_offset": pc, "owner": owner, "field_name": name, "field_descriptor": descriptor})
        elif opcode in {0xb6, 0xb7, 0xb8, 0xb9}:
            tags = {11} if opcode == 0xb9 else {10} if opcode == 0xb6 else {10, 11}
            _, name, descriptor = pool.reference(index, tags)
            require(name != "<init>" or opcode == 0xb7, "constructor called by wrong opcode")
            if opcode == 0xb9:
                args, _ = _method_descriptor(descriptor)
                require(operands[2] == 1 + sum(_slots(v) for v in args) and operands[3] == 0, "invalid invokeinterface operands")
        elif opcode == 0xba:
            pool.get(index, {18})
            require(operands[2:] == b"\0\0", "invalid invokedynamic reserved operands")
        elif opcode in {0xbb, 0xbd, 0xc0, 0xc1, 0xc5}:
            name = pool.cls(index)
            require(opcode != 0xbb or not name.startswith("["), "new instruction targets array")
            if opcode == 0xc5:
                require(1 <= operands[2] <= len(name) - len(name.lstrip("[")), "invalid multianewarray dimensions")
        elif opcode == 0xbc:
            require(4 <= index <= 11, "invalid newarray type")
    require(all(target in starts for target in targets), "bytecode branch targets non-instruction boundary")
    return starts, references


def _code_attributes(r, pool, code, starts, max_locals, max_stack):
    attributes = {}
    boundaries = set(starts) | {len(code)}
    for _ in range(r.u2()):
        name, length = pool.utf(r.u2()), r.u4()
        require(name not in attributes, "duplicate Code attribute")
        attributes[name] = r.take(length)
    allowed = {"LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable", "StackMapTable"}
    require(set(attributes) <= allowed, "unsupported Code metadata")
    for name, raw in attributes.items():
        item = Reader(raw)
        count = item.u2()
        if name == "LineNumberTable":
            for _ in range(count):
                start, _line = item.u2(), item.u2()
                require(start in starts, "invalid line-number offset")
        elif name in {"LocalVariableTable", "LocalVariableTypeTable"}:
            for _ in range(count):
                start, length, variable, descriptor, index = (item.u2() for _ in range(5))
                _name(pool.utf(variable))
                descriptor = pool.utf(descriptor)
                require(start in starts and start + length in boundaries, "invalid local-variable range")
                width = 1
                if name == "LocalVariableTable":
                    _field_descriptor(descriptor)
                    width = _slots(descriptor)
                require(index + width <= max_locals, "local-variable index out of bounds")
        else:
            offset = -1
            def verification_type():
                tag = item.u1()
                require(0 <= tag <= 8, "invalid stack-map verification tag")
                if tag == 7:
                    pool.cls(item.u2())
                elif tag == 8:
                    require(starts.get(item.u2()) == 0xbb, "invalid uninitialized stack-map offset")
                return 2 if tag in {3, 4} else 1
            for _ in range(count):
                frame = item.u1()
                if frame <= 63:
                    delta = frame
                elif frame <= 127:
                    delta = frame - 64
                    require(verification_type() <= max_stack, "stack-map stack exceeds max_stack")
                elif frame == 247:
                    delta = item.u2()
                    require(verification_type() <= max_stack, "stack-map stack exceeds max_stack")
                elif 248 <= frame <= 254:
                    delta = item.u2()
                    if frame >= 252:
                        require(sum(verification_type() for _ in range(frame - 251)) <= max_locals, "stack-map locals exceed max_locals")
                elif frame == 255:
                    delta = item.u2()
                    require(sum(verification_type() for _ in range(item.u2())) <= max_locals, "stack-map locals exceed max_locals")
                    require(sum(verification_type() for _ in range(item.u2())) <= max_stack, "stack-map stack exceeds max_stack")
                else:
                    raise ValueError("reserved stack-map frame type")
                offset += delta + 1
                require(offset in starts, "stack-map offset is not instruction boundary")
        item.end()


def _code(raw, pool, method):
    r = Reader(raw)
    max_stack, max_locals, length = r.u2(), r.u2(), r.u4()
    require(0 < length < 65536, "invalid Code length")
    args, _ = _method_descriptor(method["descriptor"])
    require(sum(_slots(arg) for arg in args) + (0 if method["access"] & 8 else 1) <= max_locals, "method parameters exceed max_locals")
    code = r.take(length)
    starts, references = _instructions(code, pool, max_locals)
    boundaries = set(starts) | {len(code)}
    for _ in range(r.u2()):
        start, end, handler, catch = r.u2(), r.u2(), r.u2(), r.u2()
        require(start in starts and end in boundaries and start < end and handler in starts, "invalid exception handler range")
        if catch:
            pool.cls(catch, binary=True)
    _code_attributes(r, pool, code, starts, max_locals, max_stack)
    r.end()
    return [{"method_name": method["name"], "method_descriptor": method["descriptor"], **ref} for ref in references]


def _inspect(raw):
    require(isinstance(raw, bytes) and len(raw) <= MAX_CLASS_BYTES, "classfile byte budget exceeded")
    parsed = java_archive.parse_class(raw)
    require((parsed["major"], parsed["minor"]) == (52, 0), "unsupported View class version (requires Java 8)")
    pool = Pool(parsed["constantPool"], int.from_bytes(raw[8:10], "big"))
    _binary_name(parsed["name"])
    if parsed["super"] is not None:
        _binary_name(parsed["super"])
    require(len(parsed["interfaces"]) == len(set(parsed["interfaces"])), "duplicate class interface")
    for interface in parsed["interfaces"]:
        _binary_name(interface)
    attrs, budget = parsed["attributes"], AnnotationBudget()
    result = {key: parsed[key] for key in ("minor", "major", "access", "name", "super", "interfaces")}
    result["metadata"] = _metadata(attrs, pool, budget, "class")
    if "SourceFile" in attrs:
        pool.utf(_index(attrs["SourceFile"]))
    if "SourceDebugExtension" in attrs:
        _mutf8(attrs["SourceDebugExtension"])
    _bootstrap(attrs, pool)
    result["innerClasses"], result["enclosingMethod"] = _inner_enclosing(attrs, pool)
    result["innerSelf"] = [entry for entry in result["innerClasses"] if entry["inner"] == parsed["name"]]
    references = []
    for kind, location in (("fields", "field"), ("methods", "method")):
        members = []
        for member in parsed[kind]:
            name, descriptor, flags = member["name"], member["descriptor"], member["access"]
            _name(name, method=kind == "methods")
            _method_descriptor(descriptor) if kind == "methods" else _field_descriptor(descriptor)
            if kind == "methods" and name in {"<init>", "<clinit>"}:
                require(_method_descriptor(descriptor)[1] == "V", "special method must return void")
                require(name != "<clinit>" or (descriptor == "()V" and flags & 8), "invalid class initializer declaration")
                require(name != "<init>" or not flags & (8 | 0x100 | 0x400), "invalid constructor flags")
            metadata = _metadata(member["attributes"], pool, budget, location, descriptor)
            constant = None
            if kind == "fields" and "ConstantValue" in member["attributes"]:
                constant = _typed_constant(pool, _index(member["attributes"]["ConstantValue"]), descriptor)
            if kind == "methods":
                code = member["attributes"].get("Code")
                require((code is None) == bool(flags & (0x100 | 0x400)), "Code attribute disagrees with native/abstract flags")
                if code is not None:
                    references.extend(_code(code, pool, member))
            if not flags & 2 and name != "<clinit>":
                members.append({"name": name, "descriptor": descriptor, "access": flags, "constant": constant, "metadata": metadata})
        result[kind] = sorted(members, key=lambda member: (member["name"], member["descriptor"]))
    return result, references


def accessible_api(raw):
    """Canonical JSON-serializable non-private API; unsupported metadata raises."""
    return _inspect(raw)[0]


def api_difference(before, after):
    old, new = accessible_api(before), accessible_api(after)
    return {key: {"reference": old[key], "candidate": new[key]} for key in old if old[key] != new[key]}


def resource_field_references(raw):
    """Actual View R$drawable/R$string GETSTATIC occurrences in every method.

    Return ordered records with method_name, method_descriptor, bytecode_offset,
    owner, field_name and field_descriptor. Multiplicity and private methods are
    retained. Offsets are diagnostic: source compilers can shift instructions
    without changing a per-method resource-use multiset. No unused pool entry,
    GETFIELD, PUTSTATIC, operand byte or inlined zero counts as a resource use.
    A GETSTATIC of another com/termux/view/R$ owner fails closed; it is not
    silently excluded from a resource-parity claim.
    """
    return _inspect(raw)[1]


def constructor_binding_evidence(raw, descriptor):
    """Diagnostic only: resolve a narrow straight-line constructor as data.

    This does not alter API gates or claim general method/runtime equivalence.
    Local slots come only from the descriptor, never MethodParameters names.
    Only parameter loads, ICONST_0, own-field stores, one direct-super <init>
    call and a final RETURN are supported. All other paths fail closed.
    """
    _inspect(raw)
    parsed = java_archive.parse_class(raw)
    constructors = [row for row in parsed["methods"] if row["name"] == "<init>"]
    require(len(constructors) == 1 and constructors[0]["descriptor"] == descriptor,
            "constructor descriptor missing or overloaded/ambiguous")
    method = constructors[0]
    pool = Pool(parsed["constantPool"], int.from_bytes(raw[8:10], "big"))
    r = Reader(method["attributes"]["Code"])
    max_stack, max_locals, length = r.u2(), r.u2(), r.u4()
    require(length <= 256, "constructor diagnostic byte budget exceeded")
    code = Reader(r.take(length))
    handlers = r.u2()
    require(handlers == 0, "constructor diagnostic forbids exception handlers")
    arguments, result = _method_descriptor(descriptor)
    require(result == "V", "constructor must return void")
    slots = {0: {"kind": "this", "slot": 0, "descriptor": "L" + parsed["name"] + ";"}}
    slot = 1
    for argument in arguments:
        slots[slot] = {"kind": "parameter", "slot": slot, "descriptor": argument}
        slot += _slots(argument)
    fields = {(row["name"], row["descriptor"]) for row in parsed["fields"] if not row["access"] & 8}
    stack, instructions, stores, calls = [], [], [], []

    def category(value):
        return "I" if value in "BCISZ" else "A" if value.startswith(("L", "[")) else value

    def pop(expected):
        require(stack and category(stack[-1]["descriptor"]) == category(expected),
                "constructor diagnostic stack/operand type mismatch")
        return stack.pop()

    returned = False
    while code.pos < len(code.raw):
        offset, opcode = code.pos, code.u1()
        require(len(instructions) < 64, "constructor diagnostic instruction budget exceeded")
        instruction = {"offset": offset, "opcode": opcode}
        if 0x15 <= opcode <= 0x19 or 0x1a <= opcode <= 0x2d:
            kind = opcode - 0x15 if opcode <= 0x19 else (opcode - 0x1a) // 4
            slot = code.u1() if opcode <= 0x19 else (opcode - 0x1a) % 4
            require(slot in slots and category(slots[slot]["descriptor"]) == "IJFDA"[kind],
                    "constructor load is not a matching parameter slot")
            stack.append(slots[slot])
            instruction.update(op="ilfda"[kind] + "load", value=slots[slot])
        elif opcode == 0x03:
            value = {"kind": "literal", "descriptor": "I", "value": 0}
            stack.append(value)
            instruction.update(op="iconst_0", value=value)
        elif opcode == 0xb5:
            owner, name, field_type = pool.reference(code.u2(), {9})
            require(owner == parsed["name"] and (name, field_type) in fields,
                    "constructor store is not an own instance field")
            value, receiver = pop(field_type), pop("Ljava/lang/Object;")
            require(receiver == slots[0], "constructor field receiver is not this")
            store = {"owner": owner, "name": name, "descriptor": field_type, "value": value}
            stores.append(store)
            instruction.update(op="putfield", **store)
        elif opcode == 0xb7:
            owner, name, call_descriptor = pool.reference(code.u2(), {10})
            call_args, call_result = _method_descriptor(call_descriptor)
            require(owner == parsed["super"] and name == "<init>" and call_result == "V" and not calls,
                    "constructor diagnostic requires one direct-super constructor call")
            values = [pop(argument) for argument in reversed(call_args)][::-1]
            require(pop("Ljava/lang/Object;") == slots[0], "constructor call receiver is not this")
            call = {"owner": owner, "name": name, "descriptor": call_descriptor, "arguments": values}
            calls.append(call)
            instruction.update(op="invokespecial", **call)
        elif opcode == 0xb1:
            require(not stack and len(calls) == 1 and code.pos == len(code.raw),
                    "constructor diagnostic requires final empty-stack return after super call")
            returned = True
            instruction.update(op="return")
        else:
            raise ValueError("unsupported constructor diagnostic opcode")
        require(sum(_slots(value["descriptor"]) for value in stack) <= max_stack,
                "constructor diagnostic stack exceeds max_stack")
        instructions.append(instruction)
    require(returned, "constructor diagnostic missing RETURN")
    return {"class": parsed["name"], "access": method["access"], "descriptor": descriptor,
            "max_stack": max_stack, "max_locals": max_locals, "exception_handlers": handlers,
            "instructions": instructions, "field_stores": stores, "super_call": calls[0]}
