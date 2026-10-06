"""Generates /shared/test-vectors/*.json with an implementation that is independent of the Rust program
(Python big integers). The program tests, the backend and the Android app all check their own
implementations against these files. Re-run only when the rules change, and review the diff.

    python scripts/gen-test-vectors.py
"""
import json
import random
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "shared" / "test-vectors"
DAY = 86_400
GRACE = 7_200


def local_day(ts, tz):
    return (ts + tz * 60) // DAY  # Python // floors, like div_euclid for a positive divisor


def day_index(now, start, tz):
    return local_day(now, tz) - local_day(start, tz)


def window_ok(now, start, tz, day):
    local_now = now + tz * 60
    ws = local_day(start, tz) * DAY + day * DAY
    return ws <= local_now < ws + DAY + GRACE


def payout(stakes, ok, penalty_bps, fee_bps):
    pen = [0 if o else s * penalty_bps // 10_000 for s, o in zip(stakes, ok)]
    forfeit = sum(pen)
    fee = forfeit * fee_bps // 10_000
    dist = forfeit - fee
    succ = sum(s for s, o in zip(stakes, ok) if o)
    pays = []
    for s, o, p in zip(stakes, ok, pen):
        if o:
            pays.append(s + dist * s // succ)
        else:
            pays.append(s - p)
    treasury = sum(stakes) - sum(pays)
    assert treasury >= fee >= 0
    # Amounts are decimal strings: values above 2^53 are not exact in JavaScript's JSON.parse.
    return {
        "stakes": [str(x) for x in stakes],
        "succeeded": ok,
        "penalty_bps": penalty_bps,
        "fee_bps": fee_bps,
        "total_forfeit": str(forfeit),
        "fee": str(fee),
        "distributable": str(dist),
        "payouts": [str(x) for x in pays],
        "treasury": str(treasury),
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(20261012)

    # ---- day index / check-in window
    base = 1_799_971_200  # UTC midnight
    day_cases, window_cases = [], []
    starts = [0, base, base + 3 * 3_600, base + 23 * 3_600 + 59 * 60, 951_782_400 + 12 * 3_600]  # last: 2000-02-29 noon
    for start in starts:
        for tz in (-720, -300, 0, 60, 120, 330, 840):
            for off in (-1, 0, 3_599, 86_399, 86_400, 86_400 + 1, 5 * 86_400 + 12_345):
                now = start + off
                day_cases.append({"now": now, "start_ts": start, "tz": tz, "day_index": day_index(now, start, tz)})
            for day in (0, 1, 59):
                ws = local_day(start, tz) * DAY + day * DAY - tz * 60  # UTC instant the window opens
                for off in (-1, 0, 1, DAY - 1, DAY, DAY + GRACE - 1, DAY + GRACE, DAY + GRACE + 1):
                    now = ws + off
                    window_cases.append(
                        {"now": now, "start_ts": start, "tz": tz, "day": day, "ok": window_ok(now, start, tz, day)}
                    )

    # ---- payouts
    payout_cases = []
    named = [
        ("two winners one loser", [100, 300, 200], [True, True, False], 10_000, 0),
        ("dust", [1, 2, 10], [True, True, False], 10_000, 0),
        ("fee 5%", [1000, 1000], [True, False], 10_000, 500),
        ("soft 30%", [500, 1000], [True, False], 3_000, 0),
        ("nobody succeeds", [100, 200], [False, False], 10_000, 0),
        ("single success", [500], [True], 10_000, 0),
        ("single failure", [500], [False], 10_000, 0),
        ("all succeed", [100, 250, 7], [True, True, True], 10_000, 250),
        ("max fee soft max penalty", [10**12, 3, 999_999], [True, False, False], 5_000, 1_000),
        ("huge stakes", [2**62, 2**62, 2**61], [True, True, False], 10_000, 1_000),
    ]
    for name, stakes, ok, pb, fb in named:
        c = payout(stakes, ok, pb, fb)
        c["name"] = name
        payout_cases.append(c)
    for i in range(40):
        n = rnd.randint(1, 8)
        stakes = [rnd.choice([1, 2, 3, rnd.randint(1, 10**6), rnd.randint(1, 10**12)]) for _ in range(n)]
        ok = [rnd.random() < 0.55 for _ in range(n)]
        if not any(ok):
            ok[0] = True if rnd.random() < 0.5 else ok[0]
        pb = rnd.choice([10_000, 5_000, rnd.randint(1, 5_000)])
        fb = rnd.choice([0, 0, 250, rnd.randint(0, 1_000)])
        c = payout(stakes, ok, pb, fb)
        c["name"] = f"random {i}"
        payout_cases.append(c)

    (OUT / "day-index.json").write_text(
        json.dumps({"checkin_grace_secs": GRACE, "day_seconds": DAY, "day_index": day_cases, "window": window_cases}, indent=1)
    )
    (OUT / "payout.json").write_text(json.dumps({"cases": payout_cases}, indent=1))
    print(f"wrote {len(day_cases)} day-index, {len(window_cases)} window, {len(payout_cases)} payout cases to {OUT}")


if __name__ == "__main__":
    main()
