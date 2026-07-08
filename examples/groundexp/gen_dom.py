#!/usr/bin/env python3
"""Generate dom/1 facts for the Ground Explosion benchmark.

Emits dom(1), dom(2), ..., dom(N), one per line. The order is preserved
(no shuffling) since the encoding's symmetry under permutation of the
domain makes order irrelevant for the lazy-grounding comparison.

Usage:
  ./gen_dom.py <N>
"""
import sys

def main():
    if len(sys.argv) != 2:
        print("Usage: gen_dom.py <N>", file=sys.stderr)
        sys.exit(2)
    n = int(sys.argv[1])
    print("\n".join(f"dom({i})." for i in range(1, n + 1)))

if __name__ == "__main__":
    main()
