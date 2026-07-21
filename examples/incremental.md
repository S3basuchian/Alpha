# Basic Incremental Solving with `AlphaSession`

Alpha exposes a multi-shot–style API via `AlphaSession`: a long-lived solving
session that accumulates rules and facts across `add(...)` calls and computes
answer sets on demand via `solve()`. This mirrors clingo's `Control` object in
spirit and is the API foundation on which future phases (true solver-state
retention, `#external` atoms, `#program` subprograms) will be layered.

The current implementation preserves the *program* across calls but re-grounds
and re-solves on each `solve()`. Behaviour is therefore identical to a single
batch solve of the accumulated program — useful for iteratively building and
inspecting a problem, even before full state retention lands.

## Running the examples

Every snippet below appears, in runnable form, in
[`IncrementalSolvingExample.java`](../alpha-cli-app/src/main/java/at/ac/tuwien/kr/alpha/app/examples/IncrementalSolvingExample.java).
Run them all at once with:

```sh
./gradlew :alpha-cli-app:runIncrementalExample
```

From an IDE, run the `main` method of
`at.ac.tuwien.kr.alpha.app.examples.IncrementalSolvingExample` directly.

The behavioural spec is in
[`AlphaSessionImplTest`](../alpha-solver/src/test/java/at/ac/tuwien/kr/alpha/api/impl/AlphaSessionImplTest.java),
runnable with:

```sh
./gradlew :alpha-solver:test --tests "at.ac.tuwien.kr.alpha.api.impl.AlphaSessionImplTest"
```

## Quick start

Build up a small reachability problem in stages, then solve once.

```java
import java.util.stream.Collectors;
import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;

public class QuickStart {
    public static void main(String[] args) {
        Alpha alpha = new AlphaImpl();
        AlphaSession session = alpha.newSession();

        session.add("node(1). node(2). node(3).");
        session.add("edge(1,2). edge(2,3).");
        session.add(
                "reachable(X) :- start(X)."
              + "reachable(Y) :- reachable(X), edge(X, Y).");
        session.add("start(1).");

        session.solve().forEach(System.out::println);
    }
}
```

Expected output (one answer set):

```
{ edge(1,2), edge(2,3), node(1), node(2), node(3), reachable(1), reachable(2), reachable(3), start(1) }
```

## Incremental reachability

Each `add` is monotone: once a rule or fact is in the session it stays until
`reset()`. So we can grow a graph one edge at a time and re-solve to watch the
set of reachable nodes expand.

```java
Alpha alpha = new AlphaImpl();
AlphaSession session = alpha.newSession();

session.add(
        "reachable(X) :- start(X)."
      + "reachable(Y) :- reachable(X), edge(X, Y).");
session.add("start(1).");

System.out.println("-- shot 1: start(1) only");
session.solve().forEach(System.out::println);
// { reachable(1), start(1) }

session.add("edge(1,2).");
System.out.println("-- shot 2: +edge(1,2)");
session.solve().forEach(System.out::println);
// { edge(1,2), reachable(1), reachable(2), start(1) }

session.add("edge(2,3). edge(3,4).");
System.out.println("-- shot 3: +edge(2,3) +edge(3,4)");
session.solve().forEach(System.out::println);
// { edge(1,2), edge(2,3), edge(3,4),
//   reachable(1), reachable(2), reachable(3), reachable(4), start(1) }
```

## Growing the search space with choice rules

Choice rules combine cleanly with incremental fact addition. The number of
answer sets here doubles each time a coin is added.

```java
Alpha alpha = new AlphaImpl();
AlphaSession session = alpha.newSession();
session.add("{ heads(C) } :- coin(C).");

for (int i = 1; i <= 4; i++) {
    session.add("coin(" + i + ").");
    long count = session.solve().count();
    System.out.printf("  %d coin(s) -> %d answer set(s)%n", i, count);
}
// 1 coin(s) -> 2 answer set(s)
// 2 coin(s) -> 4 answer set(s)
// 3 coin(s) -> 8 answer set(s)
// 4 coin(s) -> 16 answer set(s)
```

## Inspecting and resetting

`getProgram()` returns an independent snapshot of the currently-accumulated
program — useful for debugging, logging, or pinning a state to compare against
later. `reset()` returns the session to an empty state without discarding the
session object itself.

```java
Alpha alpha = new AlphaImpl();
AlphaSession session = alpha.newSession();
session.add("p(1). p(2). q(X) :- p(X).");

System.out.println("Accumulated program:");
System.out.println(session.getProgram());
// p(1).
// p(2).
// q(X) :- p(X).

session.solve().forEach(System.out::println);
// { p(1), p(2), q(1), q(2) }

session.reset();
session.solve().forEach(System.out::println);
// {}
```

## Tightening the search with added constraints

Adding a constraint between shots prunes (and can eliminate) answer sets.

```java
Alpha alpha = new AlphaImpl();
AlphaSession session = alpha.newSession();
session.add("{ a }.");
session.solve().forEach(System.out::println);
// {}
// { a }

session.add(":- a.");
session.solve().forEach(System.out::println);
// {}

session.add(":- not a.");
session.solve().forEach(System.out::println);
// (no answer sets — UNSAT)
```

## Backpropagation across timesteps

A telingo-style encoding tags atoms with an explicit timestep term and adds
one new timestep per shot. A constraint introduced at a later shot binds
against every matching ground instance in the accumulated program — including
ones whose timestep term refers to an earlier shot — so previously-feasible
truth values at earlier timesteps can be ruled out.

```java
Alpha alpha = new AlphaImpl();
AlphaSession session = alpha.newSession();

session.add("{ light(T) } :- time(T).");
session.add("time(1).");

System.out.println("-- shot 1: time(1) only");
session.solve().forEach(System.out::println);
// { light(1), time(1) }
// { time(1) }

session.add("time(2).");
session.add(":- time(T), not light(T).");

System.out.println("-- shot 2: +time(2), +':- time(T), not light(T).'");
session.solve().forEach(System.out::println);
// { light(1), light(2), time(1), time(2) }
```

In shot 1, `light(1)` is freely chosen — both truth values appear in some
answer set. Shot 2 adds `time(2)` together with a maintenance obligation
forcing the light on at every timestep; the constraint reaches back through
`time(1)` and eliminates the answer set in which `light(1)` was false.
`light(1)` is now forced true by a rule added *after* timestep 1 was
introduced — what the proposal calls *backpropagation*.

The current `AlphaSession` gets this trivially because each shot re-solves
the accumulated program. The deeper claim is that Alpha's CDCL doesn't treat
past-shot decisions as frozen — new nogoods conflict with the current
assignment regardless of which shot the underlying atom came from — so this
property is expected to persist once true solver-state retention lands.
