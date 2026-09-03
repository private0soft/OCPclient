#!/usr/bin/env python3
"""XOR-bake an HTTPS update-manifest URL into UpdateDefaults.java (key 0xA5)."""

from __future__ import print_function

import argparse
import json
import re
import sys


KEY = 0xA5
ENC_PATTERN = re.compile(
    r"private static final byte\[\] ENC = \{.*?\n\t\};",
    re.S,
)


def encode_bytes(url):
    raw = url.encode("utf-8")
    return [b ^ KEY for b in raw]


def format_java_array(enc):
    lines = []
    row = []
    for i, b in enumerate(enc):
        row.append("(byte) %d" % b)
        if len(row) == 8 or i == len(enc) - 1:
            suffix = "," if i != len(enc) - 1 else ""
            lines.append("\t\t\t" + ", ".join(row) + suffix)
            row = []
    return "\n".join(lines)


def patch_manifest(path, version_code, version_name, notes):
    """Update version fields only. Never touch the APK download url."""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    url = (data.get("url") or "").strip()
    if not url.lower().startswith("https://"):
        raise SystemExit("%s must contain an https \"url\" for the APK download" % path)
    data["versionCode"] = int(version_code)
    data["versionName"] = version_name
    data["notes"] = notes
    data["url"] = url
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    print("Updated %s to %s (%s); download url unchanged" % (path, version_name, version_code))


def bake(path, url):
    if not url.startswith("https://"):
        raise SystemExit("URL must start with https://")
    text = open(path, "r", encoding="utf-8").read()
    enc = encode_bytes(url)
    block = format_java_array(enc)
    repl = "private static final byte[] ENC = {\n%s\n\t};" % block
    new, n = ENC_PATTERN.subn(repl, text, count=1)
    if n != 1:
        raise SystemExit("Failed to find ENC array in %s" % path)
    open(path, "w", encoding="utf-8", newline="\n").write(new)
    # sanity: round-trip
    decoded = bytes([(b & 0xFF) ^ KEY for b in enc]).decode("utf-8")
    if decoded != url:
        raise SystemExit("round-trip failed")
    print("Baked update URL into %s (%d bytes, XOR 0xA5)" % (path, len(enc)))


def main(argv):
    if argv and argv[0] == "--update-json":
        if len(argv) < 4:
            raise SystemExit("usage: bake_update_url.py --update-json FILE CODE NAME [NOTES]")
        notes = argv[4] if len(argv) > 4 else ""
        patch_manifest(argv[1], argv[2], argv[3], notes)
        return
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("url", help="HTTPS update JSON URL")
    p.add_argument(
        "path",
        nargs="?",
        default="app/src/main/java/net/openconnect_vpn/android/core/UpdateDefaults.java",
        help="path to UpdateDefaults.java",
    )
    args = p.parse_args(argv)
    bake(args.path, args.url)


if __name__ == "__main__":
    main(sys.argv[1:])
