#!/usr/bin/python3 -I
"""Root-owned sender used only by the separate upstream read-only rrsync entry point."""
import os
import sys

arguments=sys.argv[1:]
if not arguments or arguments[0]!='--server' or '--sender' not in arguments or '--daemon' in arguments:
    raise SystemExit(1)
os.umask(0o077)
os.execv('/usr/local/libexec/rsync-real',['rsync',*arguments])
