"""Local DNS forwarder for the Play emulator (a test tool; nothing is stored).
- AAAA (IPv6) questions get an empty answer: the emulator advertises IPv6 it cannot route, so Google Play services tried it first and failed.
- *.googleapis.com A questions are answered with www.google.com's address: on this PC's network the 172.217.x.x addresses googleapis.com resolves to
  time out, while other Google front ends (same TLS certificates) work.
- everything else is forwarded to 8.8.8.8, then 1.1.1.1 (threaded, with retries).
Run it, then start the emulator with -dns-server 127.0.0.1. Optional argument: the Google address to use."""
import socket
import sys
import threading

UPSTREAMS = [("8.8.8.8", 53), ("1.1.1.1", 53), ("8.8.4.4", 53)]
GOOGLE_IP = bytes(int(x) for x in (sys.argv[1] if len(sys.argv) > 1 else "142.251.156.119").split("."))


def question_end(packet):
    i = 12
    while packet[i] != 0:
        i += packet[i] + 1
    return i + 5


def qtype(packet):
    return int.from_bytes(packet[question_end(packet) - 4:question_end(packet) - 2], "big")


def qname(packet):
    i, parts = 12, []
    while packet[i] != 0:
        parts.append(packet[i + 1:i + 1 + packet[i]].decode("ascii", "ignore"))
        i += packet[i] + 1
    return ".".join(parts).lower()


def answer(server, data, addr):
    try:
        question = data[12:question_end(data)]
        t = qtype(data)
        if t == 28:  # AAAA
            server.sendto(data[:2] + b"\x81\x80" + data[4:6] + b"\x00\x00\x00\x00\x00\x00" + question, addr)
            return
        if t == 1 and qname(data).endswith(".googleapis.com"):
            reply = data[:2] + b"\x81\x80" + data[4:6] + b"\x00\x01\x00\x00\x00\x00" + question
            reply += b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04" + GOOGLE_IP
            server.sendto(reply, addr)
            return
        for attempt in range(6):
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            u.settimeout(2)
            try:
                u.sendto(data, UPSTREAMS[attempt % len(UPSTREAMS)])
                server.sendto(u.recv(4096), addr)
                return
            except socket.timeout:
                continue
            finally:
                u.close()
        print("gave up on", qname(data), flush=True)
    except Exception as e:  # keep serving
        print("error", e, file=sys.stderr, flush=True)


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 53))
    print("dns helper listening on 127.0.0.1:53", flush=True)
    while True:
        data, addr = s.recvfrom(4096)
        threading.Thread(target=answer, args=(s, data, addr), daemon=True).start()


main()
