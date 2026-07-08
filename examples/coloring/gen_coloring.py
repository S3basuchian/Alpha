#!/usr/bin/env python3
"""Emit the base graph facts for the coloring benchmark: v(1..V). plus E random
undirected edges e(lo,hi).

The random draw reproduces java.util.Random byte-for-byte (same LCG, same
nextInt), so the base graph is IDENTICAL to buildBaseFacts() in
IncrementalColoringBenchmark.java for the same (V, E, seed). That lets an
external clingo solve the exact same shot-1 instance the Java driver saw.

Usage:  ./gen_coloring.py <numVertices> <numEdges> [seed]     # default seed 42
"""
import sys


class JavaRandom:
    """Faithful port of java.util.Random (48-bit LCG)."""
    _MASK = (1 << 48) - 1
    _MUL = 0x5DEECE66D
    _ADD = 0xB

    def __init__(self, seed):
        self._seed = (seed ^ self._MUL) & self._MASK

    def _next(self, bits):
        self._seed = (self._seed * self._MUL + self._ADD) & self._MASK
        val = self._seed >> (48 - bits)
        # interpret as signed 32-bit
        if val >= (1 << 31):
            val -= (1 << 32)
        return val

    def next_int(self, bound):
        if bound <= 0:
            raise ValueError("bound must be positive")
        if (bound & -bound) == bound:  # power of two
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            if bits - val + (bound - 1) >= 0:
                return val


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: gen_coloring.py <numVertices> <numEdges> [seed]")
    num_vertices = int(sys.argv[1])
    num_edges = int(sys.argv[2])
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 42

    out = [f"v({v})." for v in range(1, num_vertices + 1)]

    rnd = JavaRandom(seed)
    edge_keys = set()
    guard = 0
    while len(edge_keys) < num_edges and guard < num_edges * 50 + 1000:
        guard += 1
        a = 1 + rnd.next_int(num_vertices)
        b = 1 + rnd.next_int(num_vertices)
        if a == b:
            continue
        lo, hi = min(a, b), max(a, b)
        key = (lo, hi)
        if key not in edge_keys:
            edge_keys.add(key)
            out.append(f"e({lo},{hi}).")

    print("\n".join(out))


if __name__ == "__main__":
    main()
