#!/usr/bin/env python3
"""clingo drivers for the coupled Gardener receding-horizon loop.

Relative-time window encoding (anchored at the current state), clingo's NATIVE idiom
(1{...}1 cardinality choice — no shared-encoding handicap):

  batch : per shot, a fresh Control gets the window with anchors as FACTS -> ground+solve.
          Pays the coupled |player-diamond| x |frog-cone| grounding EVERY shot.
  mss   : one long-lived Control; anchors are #external over the whole grid, window
          grounded ONCE (extents = whole grid), per shot re-assign externals + solve.
          The standard clingo receding-horizon idiom: grounding paid once, upfront, fat.

Skittish frogs (hop strictly toward the gardener, stay when cornered), same geometry
as GardenerBenchmark: gardener (W/2,W/2), frogs (W-4,W/2),(4,W-5).

Usage: gardener_clingo.py <batch|mss> <W> <h> <frogs> <shots> <seed>
"""
import random
import sys
import time

import os

import clingo

UNCOUPLED = os.environ.get("GARDENER_UNCOUPLED", "") == "1"
DEEPEN_EVERY = int(os.environ.get("GARDENER_DEEPEN_EVERY", "0"))  # every N shots the horizon grows by 1


def window_rules(w, h, nf, anchors_external):
    r = []
    r.append("dir(1..4).")
    r.append(f"step(1..{h}).")
    if anchors_external:
        r.append(f"cell(1..{w},1..{w}).")
        r.append(f"frogid(1..{nf}).")
        r.append("#external gardRoot(C,R) : cell(C,R).")
        r.append("#external frogRoot(F,C,R) : frogid(F), cell(C,R).")
    r.append("playerAt(C,R,0) :- gardRoot(C,R).")
    r.append("danger(F,C,R,0) :- frogRoot(F,C,R).")
    r.append("1 { move(T,D) : dir(D) } 1 :- step(T).")
    r.append("playerAt(C,R2,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,1), R2=R+1, free(C,R2).")
    r.append("playerAt(C,R2,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,2), R2=R-1, free(C,R2).")
    r.append("playerAt(C2,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,3), C2=C+1, free(C2,R).")
    r.append("playerAt(C2,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,4), C2=C-1, free(C2,R).")
    r.append("playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,1), R2=R+1, wall(C,R2).")
    r.append("playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,2), R2=R-1, wall(C,R2).")
    r.append("playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,3), C2=C+1, wall(C2,R).")
    r.append("playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,4), C2=C-1, wall(C2,R).")
    r.append("haspos(T) :- playerAt(C,R,T).")
    r.append(":- step(T), not haspos(T).")
    # a frog the gardener stands on stays put (blocks the swap-through exploit)
    r.append("danger(F,C,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(C,R,T).")
    if UNCOUPLED:
        r.append(f"danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), R2=R+1, R2<={w}.")
        r.append("danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), R2=R-1, R2>=1.")
        r.append(f"danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), C2=C+1, C2<={w}.")
        r.append("danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), C2=C-1, C2>=1.")
    else:
        r.append("danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), R<PR, R2=R+1.")
        r.append("danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), R>PR, R2=R-1.")
        r.append("danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), C<PC, C2=C+1.")
        r.append("danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), C>PC, C2=C-1.")
    r.append(":- playerAt(C,R,T), danger(F,C,R,T).")
    return "\n".join(r)


def extract_plan(model, h):
    plan = {}
    for sym in model.symbols(atoms=True):
        if sym.name == "move" and len(sym.arguments) == 2:
            plan[sym.arguments[0].number] = sym.arguments[1].number
    return plan


def legal_hops(f, gard, w, walls):
    if UNCOUPLED:
        out = [(f[0] + dc, f[1] + dr) for (dc, dr) in ((0, 1), (0, -1), (1, 0), (-1, 0))
               if 1 <= f[0] + dc <= w and 1 <= f[1] + dr <= w]
        return out or [tuple(f)]
    legal = []
    if f[1] < gard[1]:
        legal.append((f[0], f[1] + 1))
    if f[1] > gard[1]:
        legal.append((f[0], f[1] - 1))
    if f[0] < gard[0]:
        legal.append((f[0] + 1, f[1]))
    if f[0] > gard[0]:
        legal.append((f[0] - 1, f[1]))
    return legal or [tuple(f)]


def read_instance(path):
    gard, frogs, walls = None, [], set()
    for line in open(path):
        p = line.split()
        if not p or p[0] == "#":
            continue
        if p[0] == "gard":
            gard = (int(p[1]), int(p[2]))
        elif p[0] == "frog":
            frogs.append((int(p[2]), int(p[3])))
        elif p[0] == "wall":
            walls.add((int(p[1]), int(p[2])))
    return gard, frogs, walls


def main():
    mode, w, h, nf, shots, seed = sys.argv[1], *[int(x) for x in sys.argv[2:7]]
    rng = random.Random(seed)
    if len(sys.argv) > 7:
        gard, frogs, walls = read_instance(sys.argv[7])
        assert len(frogs) == nf, "frog count mismatch with instance file"
    else:
        gard, walls = (w // 2, w // 2), set()
        frogs = [(w - 4, w // 2), (4, w - 5), (w // 2, 4), (5, 5)][:nf]
    wall_facts = "".join(f"free({c},{r}). " if (c, r) not in walls else f"wall({c},{r}). "
                         for c in range(1, w + 1) for r in range(1, w + 1))
    wall_facts += "".join(f"wall({c},0). wall({c},{w+1}). " for c in range(0, w + 2))
    wall_facts += "".join(f"wall(0,{r}). wall({w+1},{r}). " for r in range(1, w + 1))
    # replay: a trajectory file (8th arg) fixes the executed move + frog positions per shot, so
    # every config solves the IDENTICAL state sequence. mode "record" (clingo-batch) writes one.
    trajpath = sys.argv[8] if len(sys.argv) > 8 else None
    traj = None
    rec = None
    if mode == "record":
        rec = open(trajpath, "w")
        mode = "batch"
    elif trajpath:
        traj = []
        for line in open(trajpath):
            p = [int(x) for x in line.split()]
            traj.append((p[0], [(p[1 + 2 * i], p[2 + 2 * i]) for i in range(nf)]))
        shots = min(shots, len(traj))
    print(f"gardener-clingo mode={mode} W={w} h={h} frogs={nf} shots={shots} seed={seed} "
          f"uncoupled={UNCOUPLED} replay={bool(traj)} record={bool(rec)}")

    ctl = None
    ground_time = 0.0
    if mode == "mss":
        t0 = time.time()
        ctl = clingo.Control(["1"])
        ctl.add("base", [], window_rules(w, h, nf, anchors_external=True) + "\n" + wall_facts)
        ctl.ground([("base", [])])
        ground_time = time.time() - t0
        print(f"  one-time MSS grounding: {ground_time:.2f}s")
    prev_ext = []

    total = 0.0
    times = []
    h0 = h
    for shot in range(shots):
        if DEEPEN_EVERY > 0:
            h = h0 + (shot // DEEPEN_EVERY)  # deepen the lookahead as the walk progresses
        t0 = time.time()
        if mode == "batch":
            ctl = clingo.Control(["1"])
            anchors = [f"gardRoot({gard[0]},{gard[1]})."]
            anchors += [f"frogRoot({i+1},{c},{r})." for i, (c, r) in enumerate(frogs)]
            ctl.add("base", [], window_rules(w, h, nf, anchors_external=False) + "\n" + wall_facts + "\n".join(anchors))
            ctl.ground([("base", [])])
        else:
            for sym in prev_ext:
                ctl.assign_external(sym, False)
            prev_ext = []
            g = clingo.Function("gardRoot", [clingo.Number(gard[0]), clingo.Number(gard[1])])
            ctl.assign_external(g, True)
            prev_ext.append(g)
            for i, (c, r) in enumerate(frogs):
                fr = clingo.Function("frogRoot", [clingo.Number(i + 1), clingo.Number(c), clingo.Number(r)])
                ctl.assign_external(fr, True)
                prev_ext.append(fr)
        plan = {}
        with ctl.solve(yield_=True) as handle:
            for model in handle:
                plan = extract_plan(model, h)
                break
        sec = time.time() - t0
        total += sec
        times.append(sec)
        if not plan:
            print(f"shot {shot}: UNSAT — stopping (survived {shot} shots)")
            print(f"RESULT mode={mode} survived={shot}/{shots} totalSolve={total:.3f}")
            sys.exit(1)
        # executed move: from the trajectory in replay, else from this config's own plan
        d = traj[shot][0] if traj else plan[1]
        dc, dr = {1: (0, 1), 2: (0, -1), 3: (1, 0), 4: (-1, 0)}[d]
        tgt = (gard[0] + dc, gard[1] + dr)
        if 1 <= tgt[0] <= w and 1 <= tgt[1] <= w and tgt not in walls:
            gard = tgt  # else: bump — gardener stays put
        if traj:
            newfrogs = traj[shot][1]
        else:
            newfrogs = []
            for i, f in enumerate(frogs):
                nxt = rng.choice(legal_hops(f, gard, w, walls))
                if nxt == gard:
                    print(f"shot {shot}: CAPTURE — conformance broken")
                    sys.exit(1)
                newfrogs.append(nxt)
        if rec:
            rec.write(f"{d} " + " ".join(f"{c} {r}" for (c, r) in newfrogs) + "\n")
        frogs = newfrogs
        print(f"shot {shot:3d}: solve={sec:.3f}s gard={gard}")
    if rec:
        rec.close()
    first5 = sum(times[:5]) / min(5, len(times))
    last5 = sum(times[-5:]) / min(5, len(times))
    print(f"\nOK: {shots} shots, 0 captures.")
    extra = f" (+ {ground_time:.2f}s one-time grounding)" if mode == "mss" else ""
    print(f"total {mode} solve: {total:.3f}s{extra}  (avg first5={first5:.3f}s last5={last5:.3f}s)")
    print(f"RESULT mode={mode} survived={shots}/{shots} totalSolve={total:.3f}")


if __name__ == "__main__":
    main()
