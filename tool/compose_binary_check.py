#!/usr/bin/env python3
"""Fails when the SDK's compiled classes call an androidx method or read a field the given runtime jars lack.

Usage: tool/compose_binary_check.py <classes directory> <file listing runtime jars, one per line>

The SDK compiles against one Compose and runs on the host's. A member that a newer Compose removed or changed is a
NoSuchMethodError in the host app; this lists every such reference, also on paths the unit tests do not compose.
tool/release_check.sh runs it with the jars of the newest Compose BOM (:reception:composeCompatibilityClasspath).
"""
import os
import struct
import sys
import zipfile

OBJECT_METHODS = {"equals", "hashCode", "toString", "getClass", "clone", "finalize", "notify", "notifyAll", "wait"}


def read_class(data):
    """(name, superclass, interfaces, declared members, referenced members) of one class file."""
    count = struct.unpack(">H", data[8:10])[0]
    pool, position, index = [None] * count, 10, 1
    while index < count:
        tag = data[position]
        if tag == 1:
            length = struct.unpack(">H", data[position + 1:position + 3])[0]
            pool[index] = data[position + 3:position + 3 + length].decode("utf-8", "replace")
            position += 3 + length
        elif tag in (3, 4):
            position += 5
        elif tag in (5, 6):
            position += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack(">H", data[position + 1:position + 3])[0])
            position += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag,) + struct.unpack(">HH", data[position + 1:position + 5])
            position += 5
        elif tag == 15:
            position += 4
        else:
            raise ValueError(f"constant pool tag {tag}")
        index += 1
    class_name = lambda entry: pool[pool[entry][1]]
    _, this, superclass = struct.unpack(">HHH", data[position:position + 6])
    position += 6
    interface_count = struct.unpack(">H", data[position:position + 2])[0]
    position += 2
    interfaces = [class_name(struct.unpack(">H", data[position + 2 * i:position + 2 * i + 2])[0])
                  for i in range(interface_count)]
    position += 2 * interface_count
    declared = set()
    for kind in ("field", "method"):
        member_count = struct.unpack(">H", data[position:position + 2])[0]
        position += 2
        for _ in range(member_count):
            _, name, descriptor, attributes = struct.unpack(">HHHH", data[position:position + 8])
            position += 8
            declared.add((kind, pool[name], pool[descriptor]))
            for _ in range(attributes):
                position += 6 + struct.unpack(">I", data[position + 2:position + 6])[0]
    references = set()
    for entry in pool:
        if isinstance(entry, tuple) and entry[0] in (9, 10, 11):
            name_and_type = pool[entry[2]]
            references.add(("field" if entry[0] == 9 else "method", class_name(entry[1]),
                            pool[name_and_type[1]], pool[name_and_type[2]]))
    return class_name(this), class_name(superclass) if superclass else None, interfaces, declared, references


def runtime_classes(classpath_file):
    classes = {}
    for jar in open(classpath_file).read().split():
        if jar.endswith(".jar"):
            with zipfile.ZipFile(jar) as archive:
                for entry in archive.namelist():
                    if entry.endswith(".class") and not entry.startswith("META-INF/"):
                        parsed = read_class(archive.read(entry))
                        classes.setdefault(parsed[0], parsed)
    return classes


def resolves(classes, owner, kind, name, descriptor):
    """True when [owner] or a supertype declares the member; a platform supertype (View, Activity) may, so it counts."""
    found = classes.get(owner)
    if found is None:
        return not owner.startswith("androidx/")
    if (kind, name, descriptor) in found[3] or (kind == "method" and name in OBJECT_METHODS):
        return True
    return any(resolves(classes, parent, kind, name, descriptor)
               for parent in ([found[1]] if found[1] else []) + found[2] if parent != "java/lang/Object")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    if not os.path.isdir(sys.argv[1]):
        sys.exit(f"SDK classes directory missing: {sys.argv[1]}")
    classes = runtime_classes(sys.argv[2])
    missing = {}
    checked = 0
    for directory, _, files in os.walk(sys.argv[1]):
        for file in files:
            if not file.endswith(".class"):
                continue
            with open(os.path.join(directory, file), "rb") as handle:
                user, _, _, _, references = read_class(handle.read())
            for kind, owner, name, descriptor in references:
                if owner.startswith("androidx/"):
                    checked += 1
                    if not resolves(classes, owner, kind, name, descriptor):
                        missing.setdefault(f"{kind} {owner}.{name}{descriptor}", set()).add(user)
    print(f"{checked} androidx references checked against {len(classes)} runtime classes")
    if checked == 0:
        # An empty or stale classes directory must not pass as a clean result.
        sys.exit("no androidx references found in the SDK classes; was the library built?")
    for member, users in sorted(missing.items()):
        print(f"Missing at runtime: {member}\n    called from {', '.join(sorted(users))}", file=sys.stderr)
    sys.exit(1 if missing else 0)


main()
