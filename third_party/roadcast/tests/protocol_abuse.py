#!/usr/bin/env python3

import os
import socket
import struct
import sys
import time

MAGIC = 0x52444354
VERSION = 3
HELLO = 1
WELCOME = 2
GET_SNAPSHOT = 3
HEADER_SIZE = 32


def header(
    message_type: int,
    payload_bytes: int = 0,
    *,
    magic: int = MAGIC,
    version: int = VERSION,
    flags: int = 0,
) -> bytes:
    return struct.pack(
        ">IHHIIQQ", magic, version, message_type, flags, payload_bytes, 0, 0
    )


def hello() -> bytes:
    payload = struct.pack(">HHI", VERSION, VERSION, 3)
    return header(HELLO, len(payload)) + payload


def connect(path: str) -> socket.socket:
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(1.0)
    client.connect(path)
    return client


def send_case(path: str, payload: bytes) -> None:
    with connect(path) as client:
        try:
            client.sendall(payload)
            while client.recv(4096):
                pass
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            pass


def read_exact(client: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = client.recv(length - len(result))
        if not chunk:
            raise RuntimeError("server closed a valid fragmented session")
        result.extend(chunk)
    return bytes(result)


def test_fragmented_hello(path: str) -> None:
    with connect(path) as client:
        for byte in hello():
            client.send(bytes([byte]))
            time.sleep(0.001)
        response = read_exact(client, HEADER_SIZE)
        magic, version, message_type, flags, payload_bytes, _, _ = struct.unpack(
            ">IHHIIQQ", response
        )
        if (
            magic != MAGIC
            or version != VERSION
            or message_type != WELCOME
            or flags != 0
        ):
            raise RuntimeError("invalid response to fragmented HELLO")
        read_exact(client, payload_bytes)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} socket-path", file=sys.stderr)
        return 2
    path = sys.argv[1]

    cases = [
        header(HELLO, magic=0),
        header(HELLO, payload_bytes=0xFFFFFFFF),
        header(HELLO, flags=1),
        header(HELLO, version=VERSION + 1),
        header(GET_SNAPSHOT),
        hello() + hello(),
        os.urandom(4096),
    ]
    for payload in cases:
        send_case(path, payload)
    test_fragmented_hello(path)
    print(f"protocol abuse cases passed: {len(cases) + 1}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
