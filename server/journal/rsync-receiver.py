#!/usr/bin/python3 -I
"""Upstream rrsync calls this root-owned wrapper after validating its receiver arguments."""
import os
import sys

arguments=sys.argv[1:]
if not arguments or arguments[0]!='--server' or '--sender' in arguments or '--daemon' in arguments:
    raise SystemExit(1)
try:boundary=arguments.index('--') if '--' in arguments else arguments.index('.')
except ValueError:raise SystemExit(1)
forced=['--perms','--no-owner','--no-group','--no-acls','--no-xattrs',
        '--chmod=Du=rwx,Dgo=,Fu=rw,Fgo=']
os.umask(0o077)
os.execv('/usr/local/libexec/rsync-real',['rsync',*arguments[:boundary],*forced,*arguments[boundary:]])
