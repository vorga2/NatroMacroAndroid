#!/usr/bin/env python3
import hashlib
import sys

# Public reproducible DEVELOPMENT signing identity.
# It is intentionally not a secret; it only keeps CI APK updates compatible.
SEED = b"NatroMacroAndroid/vorga2/stable-dev-signing-v1"

N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551
P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
A = (P - 3) % P
GX = 0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296
GY = 0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5


def inv(x):
    return pow(x, P - 2, P)


def add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if p1 == p2:
        m = ((3 * x1 * x1 + A) * inv(2 * y1)) % P
    else:
        m = ((y2 - y1) * inv((x2 - x1) % P)) % P
    x3 = (m * m - x1 - x2) % P
    y3 = (m * (x1 - x3) - y1) % P
    return x3, y3


def mul(k, point):
    out = None
    cur = point
    while k:
        if k & 1:
            out = add(out, cur)
        cur = add(cur, cur)
        k >>= 1
    return out


def der_len(n):
    if n < 128:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def tlv(tag, body):
    return bytes([tag]) + der_len(len(body)) + body


def seq(*xs):
    return tlv(0x30, b"".join(xs))


def integer(n):
    b = n.to_bytes(max(1, (n.bit_length() + 7) // 8), "big")
    if b[0] & 0x80:
        b = b"\0" + b
    return tlv(0x02, b)


def octet(b):
    return tlv(0x04, b)


def bitstr(b):
    return tlv(0x03, b"\0" + b)


def oid(raw):
    return tlv(0x06, raw)


def ctx(tag, body):
    return tlv(0xA0 + tag, body)


OID_EC_PUBLIC_KEY = bytes.fromhex("2a8648ce3d0201")
OID_PRIME256V1 = bytes.fromhex("2a8648ce3d030107")

d = int.from_bytes(hashlib.sha256(SEED).digest(), "big") % (N - 1) + 1
qx, qy = mul(d, (GX, GY))
pub = b"\x04" + qx.to_bytes(32, "big") + qy.to_bytes(32, "big")
ec_private = seq(
    integer(1),
    octet(d.to_bytes(32, "big")),
    ctx(0, oid(OID_PRIME256V1)),
    ctx(1, bitstr(pub)),
)
pkcs8 = seq(
    integer(0),
    seq(oid(OID_EC_PUBLIC_KEY), oid(OID_PRIME256V1)),
    octet(ec_private),
)
out = sys.argv[1] if len(sys.argv) > 1 else "natro-dev-key.pk8"
with open(out, "wb") as f:
    f.write(pkcs8)
print(out)
