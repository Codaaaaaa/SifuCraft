"""Address one approved opt-in Native diagnostic request. No game memory access.

The game validates scope/activation/idle/geometry/thread again before any call.
Observe never moves an actor. Move requests are limited to one <=10cm axis step.
"""
import argparse
import ctypes as C
import json
import math
from pathlib import Path
import re
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = ('observe', 'move', 'observe-ai', 'hold-ai')
    parser.add_argument('action', choices=actions)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--target', required=True, help='exact EnemyTable id/spawn/UObject serial')
    parser.add_argument('--x', type=float, default=0)
    parser.add_argument('--y', type=float, default=0)
    args = parser.parse_args()
    game = args.game_dir.resolve(strict=True)
    control = game / 'SifuCraft'
    opt_in = 'npc-ai-probe' if args.action.endswith('-ai') else 'npc-motion-probe'
    if not (game / 'Sifu-Win64-Shipping.exe').is_file() or not (control / opt_in).is_file():
        parser.error(f'expected Sifu binaries directory and explicit {opt_in} opt-in file')
    if not math.isfinite(args.x) or not math.isfinite(args.y):
        parser.error('step must be finite')
    if args.action == 'move' and not ((args.x == 0) != (args.y == 0) and abs(args.x) + abs(args.y) <= 10):
        parser.error('move must be one finite horizontal axis step <=10cm')
    if args.action != 'move' and (args.x or args.y):
        parser.error('observation and AI hold require a zero step')
    target = tuple(int(x) for x in args.target.split('/'))
    if len(target) != 3 or not all(x > 0 for x in target):
        parser.error('target must contain three positive identity values')
    log_path = control / 'logs/probe.log'
    if time.time() - log_path.stat().st_mtime > 5:
        parser.error('Native log is stale')
    log = log_path.read_text(encoding='utf-8')
    pid = int(re.findall(r'SifuCraft native [^\n]*, pid=(\d+)', log)[-1])
    scopes = re.findall(r'\[npc-motion\] scope native=(\d+) peer=(\d+) world=(\d+)', log)
    if not scopes:
        parser.error('no ready Native diagnostic world scope')
    native, peer, world = map(int, scopes[-1])
    k = C.WinDLL('kernel32', use_last_error=True)
    k.OpenProcess.argtypes = [C.c_uint32, C.c_int, C.c_uint32]
    k.OpenProcess.restype = C.c_void_p
    k.GetExitCodeProcess.argtypes = [C.c_void_p, C.POINTER(C.c_uint32)]
    k.QueryFullProcessImageNameW.argtypes = [C.c_void_p, C.c_uint32, C.c_wchar_p, C.POINTER(C.c_uint32)]
    k.CloseHandle.argtypes = [C.c_void_p]
    k.GetTickCount64.restype = C.c_uint64
    handle = k.OpenProcess(0x1000, False, pid)  # QUERY_LIMITED_INFORMATION only
    if not handle:
        raise C.WinError(C.get_last_error())
    try:
        status = C.c_uint32()
        name = C.create_unicode_buffer(32768)
        size = C.c_uint32(len(name))
        if not k.GetExitCodeProcess(handle, C.byref(status)) or status.value != 259:
            parser.error('logged Native process is no longer running')
        if not k.QueryFullProcessImageNameW(handle, 0, name, C.byref(size)) or Path(name.value).resolve() != game / 'Sifu-Win64-Shipping.exe':
            parser.error('logged PID does not identify this Sifu executable')
    finally:
        k.CloseHandle(handle)
    now = k.GetTickCount64()
    fields = [actions.index(args.action), now, now, native, peer, world, *target, args.x, args.y]
    # A complete line is published atomically; partial writes never become a command.
    request = control / 'npc-motion-request.txt'
    temporary = control / 'npc-motion-request.tmp'
    temporary.write_text(' '.join(str(x) for x in fields) + '\n', encoding='ascii')
    temporary.replace(request)
    print(json.dumps(dict(id=now, action=args.action, native=native, peer=peer, world=world, target=target, x=args.x, y=args.y)))


if __name__ == '__main__':
    main()
