"""Check generated source and platform icon assets without image dependencies."""
import json
import pathlib
import struct
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
source = root / "AppStore/icon.png"

def png(path, size):
    blob = path.read_bytes()
    assert blob[:8] == b"\x89PNG\r\n\x1a\n", path
    width, height, depth, color = struct.unpack_from(">IIBB", blob, 16)
    assert (width, height, depth, color) == (size, size, 8, 2), (path, width, height, depth, color)

png(source, 1024)
icon = root / "iosApp/PitchPact/Assets.xcassets/AppIcon.appiconset"
assert (icon / "icon.png").read_bytes() == source.read_bytes()
assert json.loads((icon / "Contents.json").read_text())["images"][0]["filename"] == "icon.png"
res = root / "androidApp/src/main/res"
assert (res / "drawable-nodpi/ic_launcher_foreground.png").read_bytes() == source.read_bytes()
for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    png(res / f"mipmap-{density}/ic_launcher.png", size)
ET.parse(res / "mipmap-anydpi-v26/ic_launcher.xml")
assert '@mipmap/ic_launcher' in (root / "androidApp/src/main/AndroidManifest.xml").read_text()
print("opaque RGB 1024 icon; iOS source; Android adaptive + five legacy densities OK")
