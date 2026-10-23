"""Minimal zipalign: rewrites an APK so every stored (uncompressed) entry starts on a 4-byte boundary."""
import sys, zipfile

src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, "w")
for info in zin.infolist():
    data = zin.read(info.filename)
    ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    ni.compress_type = info.compress_type
    ni.external_attr = info.external_attr
    if info.compress_type == zipfile.ZIP_STORED:
        # local header = 30 bytes + name + extra; pad extra so data offset % 4 == 0
        offset = zout.fp.tell() + 30 + len(ni.filename.encode("utf-8"))
        pad = (4 - offset % 4) % 4
        ni.extra = b"\x00" * pad
    zout.writestr(ni, data)
zout.close()
