"""Narrow static inspection of pinned-compiler, unstripped Termux ELF payloads."""
import struct
from current_native_jni_guard import ABIS, require


class Elf:
    def __init__(self, data, abi):
        self.data, self.abi = data, abi
        require(abi in ABIS and len(data) >= 64 and data[:7] == b'\x7fELF' + bytes([ABIS[abi][0], 1, 1]), 'wrong ELF identity')
        self.wide = data[4] == 2
        self.pointer_size = 8 if self.wide else 4
        h = self.unpack('HHIQQQIHHHHHH' if self.wide else 'HHIIIIIHHHHHH', 16)
        require(h[:3] == (3, ABIS[abi][1], 1), 'wrong shared-object machine/type')
        _, _, _, _, phoff, shoff, _, ehsize, phsize, phnum, shsize, shnum, _ = h
        require(ehsize == (64 if self.wide else 52) and phsize == (56 if self.wide else 32) and shsize == (64 if self.wide else 40), 'wrong ELF structure size')
        require(phnum > 0 and shnum > 0, 'missing ELF tables')
        self.programs = []
        for i in range(phnum):
            p = self.unpack('IIQQQQQQ' if self.wide else 'IIIIIIII', phoff + i * phsize)
            if self.wide: typ, flags, offset, address, _, size, memory, align = p
            else: typ, offset, address, _, size, memory, flags, align = p
            require(offset + size <= len(data) and size <= memory, 'invalid program range')
            self.programs.append(dict(type=typ, flags=flags, offset=offset, address=address, size=size, memory=memory, align=align))
        self.sections = [self.unpack('IIQQQQIIQQ' if self.wide else 'IIIIIIIIII', shoff + i * shsize) for i in range(shnum)]
        self.loads = [p for p in self.programs if p['type'] == 1]
        require(self.loads, 'no LOAD segments')
        for p in self.loads:
            require(p['align'] >= 16384 and p['align'] & (p['align']-1) == 0 and (p['address']-p['offset']) % p['align'] == 0, 'LOAD alignment not 16KiB')
            require(p['flags'] & 3 != 3, 'writable executable LOAD')
        stacks = [p for p in self.programs if p['type'] == 0x6474e551]
        require(len(stacks) == 1 and not stacks[0]['flags'] & 1, 'NX stack absent')
        require(any(p['type'] == 0x6474e552 and p['size'] for p in self.programs), 'RELRO header absent')
        dynamic = self.one_section(6)
        segments = [p for p in self.programs if p['type'] == 2]
        require(len(segments) == 1 and (segments[0]['offset'], segments[0]['address'], segments[0]['size'], segments[0]['memory']) == (dynamic[4], dynamic[3], dynamic[5], dynamic[5]), 'PT_DYNAMIC/section mismatch')
        self.mapped_section(dynamic)
        entry = 16 if self.wide else 8
        require(dynamic[9] == entry and dynamic[5] % entry == 0, 'bad dynamic table')
        self.tags = {}
        terminated = False
        for offset in range(dynamic[4], dynamic[4]+dynamic[5], entry):
            tag, value = self.unpack('qQ' if self.wide else 'iI', offset)
            if tag == 0:
                terminated = True
                break
            self.tags.setdefault(tag, []).append(value)
        require(terminated, 'unterminated dynamic table')
        self.dynamic_symbols = self.one_section(11)
        strings = self.linked_strings(dynamic)
        require(self.dynamic_symbols[6] == dynamic[6], 'dynamic/symbol string tables differ')
        self.mapped_section(strings)
        self.mapped_section(self.dynamic_symbols)
        for tag, value in ((5, strings[3]), (10, strings[5]), (6, self.dynamic_symbols[3]), (11, self.dynamic_symbols[9])):
            require(self.tags.get(tag) == [value], 'loader table pointer/size mismatch')
        self.dynstr = self.content(strings)
        require(not any(t in self.tags for t in (15,22,29)) and not any(v & 4 for v in self.tags.get(30, [])), 'RPATH/RUNPATH/TEXTREL forbidden')
        require(24 in self.tags or any(v & 8 for v in self.tags.get(30, [])) or any(v & 1 for v in self.tags.get(0x6ffffffb, [])), 'BIND_NOW absent')
        require(self.tags.get(14) and [self.string(self.dynstr, x) for x in self.tags[14]] == ['libtermux.so'], 'wrong SONAME')
        needed = [self.string(self.dynstr, x) for x in self.tags.get(1, [])]
        require(len(needed) == 3 and set(needed) == {'libc.so','libm.so','libdl.so'}, 'wrong Termux dependencies')
        self.android_identity()

    def unpack(self, fmt, offset):
        require(0 <= offset <= len(self.data)-struct.calcsize('<'+fmt), 'truncated ELF')
        return struct.unpack_from('<'+fmt, self.data, offset)

    def mapped(self, address, size, executable=False):
        require(size > 0, 'empty loader range')
        matches = [p['offset']+address-p['address'] for p in self.loads if p['address'] <= address and address+size <= p['address']+p['size'] and (not executable or p['flags'] & 1)]
        require(len(matches) == 1, 'address not uniquely loader mapped')
        return matches[0]

    def mapped_section(self, section):
        require(self.mapped(section[3], section[5]) == section[4], 'section is not loader-backed')

    def one_section(self, kind):
        values = [s for s in self.sections if s[1] == kind]
        require(len(values) == 1, 'missing/duplicate ELF section type '+str(kind))
        return values[0]

    def content(self, section):
        require(section[4]+section[5] <= len(self.data), 'truncated section')
        return self.data[section[4]:section[4]+section[5]]

    def linked_strings(self, section):
        require(section[6] < len(self.sections) and self.sections[section[6]][1] == 3, 'bad string table link')
        return self.sections[section[6]]

    @staticmethod
    def string(data, offset):
        require(0 <= offset < len(data), 'bad string pointer')
        end = data.find(b'\0', offset)
        require(end >= 0, 'unterminated string')
        return data[offset:end].decode('ascii')

    def symbols(self, section):
        entry = 24 if self.wide else 16
        require(section[9] == entry and section[5] % entry == 0, 'bad symbol table')
        strings = self.content(self.linked_strings(section))
        result = []
        for offset in range(section[4], section[4]+section[5], entry):
            if self.wide: name, info, visibility, index, address, size = self.unpack('IBBHQQ', offset)
            else: name, address, size, info, visibility, index = self.unpack('IIIBBH', offset)
            result.append(dict(name=self.string(strings,name), info=info, visibility=visibility & 3, index=index, address=address, size=size))
        return result

    def function(self, symbol):
        require(symbol['info'] & 15 == 2 and 0 < symbol['index'] < len(self.sections), 'not a defined function')
        address = symbol['address'] & ~1 if self.abi == 'armeabi-v7a' else symbol['address']
        self.mapped(address, symbol['size'], executable=True)
        section = self.sections[symbol['index']]
        require(section[2] & 6 == 6 and section[3] <= address and address+symbol['size'] <= section[3]+section[5], 'function outside executable section')

    def android_identity(self):
        values = []
        for section in self.sections:
            if section[1] != 7: continue
            notes = self.content(section); offset = 0
            while offset < len(notes):
                require(offset+12 <= len(notes), 'truncated note')
                n, d, typ = struct.unpack_from('<III', notes, offset)
                begin = offset+12; desc = begin+((n+3)&~3); end = desc+((d+3)&~3)
                require(end <= len(notes), 'invalid note range')
                if notes[begin:begin+n] == b'Android\0' and typ == 1:
                    self.mapped_section(section)
                    require(d == 132, 'wrong Android note shape')
                    value = notes[desc:desc+d]
                    values.append((struct.unpack_from('<I',value)[0], value[4:68].split(b'\0')[0], value[68:132].split(b'\0')[0]))
                offset = end
        require(values == [(29,b'r28c',b'13676358')], 'wrong emitted Android API/NDK')

    def relative_relocations(self):
        result = {}
        relative_type = {'arm64-v8a':1027,'armeabi-v7a':23,'x86':8,'x86_64':8}[self.abi]
        unsupported = {35, 36, 37, 0x6000000f, 0x60000010, 0x60000011, 0x60000012,
                       0x6fffe000, 0x6fffe001, 0x6fffe002, 0x6fffe003}
        require(not unsupported.intersection(self.tags), 'unsupported packed/RELR relocation format')
        groups = []
        for pointer_tag, size_tag, entry_tag, explicit in ((7,8,9,True),(17,18,19,False)):
            if pointer_tag not in self.tags:
                require(size_tag not in self.tags and entry_tag not in self.tags, 'orphan relocation metadata')
                continue
            require(all(len(self.tags.get(tag, [])) == 1 for tag in (pointer_tag,size_tag,entry_tag)), 'duplicate/missing relocation metadata')
            groups.append((self.tags[pointer_tag][0], self.tags[size_tag][0], self.tags[entry_tag][0], explicit))
        if 23 in self.tags:
            require(all(len(self.tags.get(tag, [])) == 1 for tag in (23,2,20)), 'invalid JMPREL metadata')
            require(self.tags[20][0] in (7,17), 'unsupported PLT relocation encoding')
            explicit = self.tags[20][0] == 7
            groups.append((self.tags[23][0], self.tags[2][0], self.pointer_size*(3 if explicit else 2), explicit))
        else:
            require(2 not in self.tags and 20 not in self.tags, 'orphan JMPREL metadata')
        for address, size, entry, explicit in groups:
            expected = self.pointer_size * (3 if explicit else 2)
            require(entry == expected and size > 0 and size % entry == 0, 'bad relocation entry size')
            base = self.mapped(address, size)
            fmt = ('QQq' if explicit else 'QQ') if self.wide else ('IIi' if explicit else 'II')
            for pos in range(base, base+size, entry):
                values = self.unpack(fmt,pos); target, info = values[:2]
                typ = info & (0xffffffff if self.wide else 0xff)
                symbol = info >> (32 if self.wide else 8)
                require(target % self.pointer_size == 0, 'unaligned relocation destination')
                require(target not in result, 'duplicate relocation destination')
                writable = [segment for segment in self.loads
                            if segment['flags'] & 2 and segment['address'] <= target
                            and target + self.pointer_size <= segment['address'] + segment['size']]
                require(len(writable) == 1, 'relocation target not writable at load')
                # All loader relocation destinations participate, including JMPREL.
                # A later PLT relocation must not overwrite a protected table pointer.
                self.mapped(target,self.pointer_size)
                addend = values[2] if explicit else self.unpack('Q' if self.wide else 'I',self.mapped(target,self.pointer_size))[0]
                result[target] = dict(type=typ,symbol=symbol,addend=addend,relative=typ==relative_type and symbol==0)
        return result

    def read_pointer_string(self, pointer):
        start = self.mapped(pointer,1)
        for length in range(1,4097):
            self.mapped(pointer,length)
            if self.data[start+length-1] == 0:
                return self.data[start:start+length-1].decode('ascii')
        raise ValueError('unbounded registration string')


def inspect(data, abi, contract):
    elf = Elf(data,abi)
    dynamic = elf.symbols(elf.dynamic_symbols)
    exported = [s for s in dynamic if s['name'] and s['index'] and s['info'] >> 4 in (1,2) and s['visibility'] in (0,3)]
    require([s['name'] for s in exported] == ['JNI_OnLoad'], 'Termux dynamic export set changed')
    elf.function(exported[0])
    imports = sorted(s['name'] for s in dynamic if s['name'] and not s['index'] and s['info'] >> 4 in (1,2))
    require(imports == contract['importsByAbi'][abi], 'Termux import set changed')
    symbols = elf.symbols(elf.one_section(2))
    def exact(name):
        matches = [s for s in symbols if s['name'] == name]
        require(len(matches)==1, 'missing/decoy local symbol: '+name)
        return matches[0]
    table = exact('owned_methods')
    require(table['info'] & 15 == 1 and 0 < table['index'] < len(elf.sections), 'registration table is not defined object')
    require(table['size'] == len(contract['methods'])*3*elf.pointer_size, 'registration count/size changed')
    elf.mapped(table['address'],table['size'])
    owner = elf.sections[table['index']]
    require(owner[2] & 2 and owner[3] <= table['address'] and table['address']+table['size'] <= owner[3]+owner[5], 'registration table outside owning section')
    relocations = elf.relative_relocations(); rows = []
    for index, expected in enumerate(contract['methods']):
        pointers = []
        for column in range(3):
            address = table['address']+(index*3+column)*elf.pointer_size
            relocation = relocations.get(address)
            require(relocation is not None and relocation['relative'], 'registration slot lacks relative relocation')
            pointers.append(relocation['addend'])
        name, descriptor = map(elf.read_pointer_string,pointers[:2])
        require((name,descriptor)==(expected['name'],expected['descriptor']), 'registration name/descriptor/order changed')
        function = exact(expected['function']); elf.function(function)
        require(pointers[2] == function['address'], 'registration function target changed')
        rows.append(dict(name=name,descriptor=descriptor,function=expected['function'],functionAddress=pointers[2],functionSize=function['size'],slotAddress=table['address']+index*3*elf.pointer_size))
    return dict(androidApi=29,androidNdk='r28c/13676358',exports=['JNI_OnLoad'],imports=imports,registrationTable=rows,
                registrationPathVerified=False,finalJavaBindingVerified=False,
                scope='Loader-relative static table and function identities; not JNI_OnLoad control flow or Android execution')
