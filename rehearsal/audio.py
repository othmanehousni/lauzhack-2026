#!/usr/bin/env python3
"""rehearsal/audio.py RECORDING [LOG]: for each utterance the ear wrote in the log while RECORDING was kept (a file in
~/Library/Logs/Moliere/audio/), where its voice starts, on the log's clock and in the file, how long it is, how loud,
and how loud the room was in the half second before it, in dB of full scale. LOG is moliere.log or moliere.1.log there
unless given. The voice is looked for from 2.5 s before the utterance's first words in the log to 1.1 s before its final
text (the ear ends an utterance 1.2 s after the voice): the 20 ms frames there within 25 dB of the loudest one.
To hear a recording again: Molière --audio RECORDING."""
import array, math, operator, os, re, struct, sys

FRAME = 0.02
T = re.compile(r'^(\d\d):(\d\d):(\d\d)\.(\d\d\d)  (.*)$')


def caf(path):
    """The first channel of a 16-bit PCM CAF, and its rate. A file whose writer was stopped reads up to its last write."""
    b = open(path, 'rb').read()
    i = 8
    while i < len(b):
        kind, size = b[i:i + 4], struct.unpack('>q', b[i + 4:i + 12])[0]
        i += 12
        if kind == b'desc':
            rate, fmt, flags, _, _, channels, bits = struct.unpack('>d4s5I', b[i:i + 32])
            if (fmt, bits) != (b'lpcm', 16): sys.exit(f'{path}: not 16-bit PCM')
        elif kind == b'data':
            data = b[i + 4:len(b) if size < 0 else i + size]
            s = array.array('h', data[:len(data) // (2 * channels) * 2 * channels])
            if bool(flags & 2) != (sys.byteorder == 'little'): s.byteswap()
            return s[::channels], rate
        i += size
    sys.exit(f'{path}: no audio')


def sec(h, m, s, ms): return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000


def clock(t): return f'{int(t // 3600):02d}:{int(t // 60 % 60):02d}:{t % 60:06.3f}'


def utterances(log, name):
    """The first sample's time, and (time of the first words, time of the final text, the text) per utterance."""
    t0, first, utts = None, None, []
    for line in open(log, errors='ignore'):
        m = T.match(line.rstrip('\n'))
        if not m: continue
        t, body = sec(*m.groups()[:4]), m[5]
        r = re.match(r'ear: recording (.*), its first sample at (\d\d):(\d\d):(\d\d)\.(\d\d\d)$', body)
        if r and t0 is not None or body.startswith(f'ear: recorded {name},'): break
        if r and r[1] == name: t0 = sec(*r.groups()[1:])
        e = re.match(r'ear     (live |quiet|final)  "(.*)"  unchanged', body)
        if t0 is None or not e: continue
        first = first or t
        if e[1] == 'final': utts.append((first, t, e[2])); first = None
    return t0, utts


def main():
    rec = sys.argv[1]
    name, logs = os.path.basename(rec), sys.argv[2:] or [os.path.expanduser(f'~/Library/Logs/Moliere/{f}') for f in ('moliere.log', 'moliere.1.log')]
    for log in filter(os.path.exists, logs):
        t0, utts = utterances(log, name)
        if t0 is not None: break
    else:
        sys.exit(f'no "ear: recording {name}" in {" or ".join(logs)}')
    s, rate = caf(rec)
    n = int(rate * FRAME)
    energy = [sum(map(operator.mul, s[k:k + n], s[k:k + n])) for k in range(0, len(s) - n + 1, n)]

    def db(a, b):
        a, b = max(0, a), min(len(energy), b)
        return 10 * math.log10(max(sum(energy[a:b]) / max(1, (b - a) * n) / 32768 ** 2, 1e-10))

    print(f'{name}: {len(s) / rate:.2f} s at {rate:.0f} Hz from {clock(t0)}, {len(utts)} utterances, {db(0, len(energy)):.1f} dB overall')
    print(f'{"starts":>12}  {"in file":>7}  {"length":>6}  {"voice dB":>8}  {"room dB":>7}  words')
    done = 0
    for first, final, text in utts:
        a, b = max(done, int((first - t0 - 2.5) / FRAME)), min(len(energy), int((final - t0 - 1.1) / FRAME))
        if b <= a: continue
        peak = max(energy[a:b])
        loud = [k for k in range(a, b) if energy[k] * 10 ** 2.5 >= peak]
        start, end = loud[0], loud[-1] + 1
        done = end
        print(f'{clock(t0 + start * FRAME):>12}  {start * FRAME:7.2f}  {(end - start) * FRAME:6.2f}  {db(start, end):8.1f}  '
              f'{db(start - int(0.5 / FRAME), start):7.1f}  "{text}"')


if __name__ == '__main__':
    main()
