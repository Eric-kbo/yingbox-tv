"""Check native LOAD segment alignment in a built APK without extracting it."""
import struct
import sys
import zipfile

apk = sys.argv[1]
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.startswith("lib/") or not name.endswith(".so"):
            continue
        data = archive.read(name)
        elf64 = data[4] == 2
        endian = "<" if data[5] == 1 else ">"
        if elf64:
            offset = struct.unpack_from(endian + "Q", data, 32)[0]
            entry_size, count = struct.unpack_from(endian + "HH", data, 54)
        else:
            offset = struct.unpack_from(endian + "I", data, 28)[0]
            entry_size, count = struct.unpack_from(endian + "HH", data, 42)
        aligns = []
        for index in range(count):
            entry = offset + index * entry_size
            if struct.unpack_from(endian + "I", data, entry)[0] == 1:
                aligns.append(struct.unpack_from(endian + ("Q" if elf64 else "I"), data, entry + (48 if elf64 else 28))[0])
        minimum = min(aligns)
        print(name, "LOAD alignment", minimum)
        if ("arm64-v8a/" in name or "x86_64/" in name) and minimum < 16384:
            raise SystemExit("64-bit native library lacks 16 KiB segment alignment")
print("All 64-bit native libraries support 16 KiB LOAD alignment")
