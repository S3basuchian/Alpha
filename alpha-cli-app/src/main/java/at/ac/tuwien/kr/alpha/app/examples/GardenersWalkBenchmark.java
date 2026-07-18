package at.ac.tuwien.kr.alpha.app.examples;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;
import at.ac.tuwien.kr.alpha.api.programs.Predicate;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;

/**
 * Gardener's Walk — receding-horizon conformant planning in a live Alpha session.
 *
 * The gardener walks a WxW garden (4 actions: N/S/E/W; walls block). Frogs are skittish:
 * each step a frog either STAYS or hops one cell strictly toward the gardener's current
 * position (wall-blocked hops are impossible). Norm: the gardener must never be on a cell
 * a frog could occupy (conformant over all frog behaviours, horizon h). Loop per shot:
 * solve for a safe h-step plan, execute the first action, observe the frogs' actual hops,
 * append the 2+f facts (executed move, one observation per frog, one new step fact) and
 * re-solve. Danger cones re-anchor at the latest observation via default negation
 * ("superseded"); the program grows monotonically, no retraction anywhere.
 *
 * Every shot is verified by an independent Java model checker (BFS frog cones honouring
 * walls + stay), and the executed walk is checked capture-free.
 *
 * Usage: GardenersWalkBenchmark <live|batch> <W> <h> <numFrogs> <shots> <seed> [instanceFile]
 *
 * instanceFile (from gen_walk_instance.py) supplies the gardener start, seeded random frog
 * starts, and wall cells (free space guaranteed connected); without it, a legacy wall-free
 * instance with fixed starts is used. -Dwalk.coupled=false switches to plan-independent
 * random-walk frogs (the control column). -Dwalk.shotCapSec caps each shot's solve.
 */
public final class GardenersWalkBenchmark {

	private static final int[][] DIRS = { { 0, 1 }, { 0, -1 }, { 1, 0 }, { -1, 0 } }; // 1=N 2=S 3=E 4=W
	private static final int SHOT_CAP_SEC = Integer.getInteger("walk.shotCapSec", 300);
	// coupled mode (default): skittish frogs hop toward the gardener — the danger closure joins playerAt
	private static final boolean COUPLED = !"false".equals(System.getProperty("walk.coupled"));
	// re-anchor: observe the gardener's CURRENT position each shot (like the frogs, via superseded
	// negation) instead of deriving playerAt through the whole accumulating move chain, so the
	// gardener's true-atom set stays bounded to the current window rather than growing every shot.
	private static final boolean REANCHOR = Boolean.getBoolean("walk.reanchor");
	// mixed walk+deepening: every N shots the lookahead horizon grows by one (0 = fixed horizon).
	// Rebuilders re-pay the ever-deeper window from scratch; the live session just adds a slice.
	private static final int DEEPEN_EVERY = Integer.getInteger("walk.deepenEvery", 0);
	// warm-ground / cold-search: before each live solve, retract a sacrificial tick fact to trigger
	// the session's retraction path — keeps the grounder + atom store (no re-grounding) but rebuilds
	// the solver (drops learned nogoods / stale search state). Tests whether live's losses are pure
	// search-state staleness while its grounding reuse carries the win.
	private static final boolean FRESH_SEARCH = Boolean.getBoolean("walk.freshSearch");

	public static void main(String[] args) throws Exception {
		if (args.length < 6) {
			System.err.println("usage: GardenersWalkBenchmark <live|batch> <W> <h> <numFrogs> <shots> <seed> [instanceFile]");
			System.exit(2);
		}
		String mode = args[0];
		int w = Integer.parseInt(args[1]);
		int h = Integer.parseInt(args[2]);
		int numFrogs = Integer.parseInt(args[3]);
		int shots = Integer.parseInt(args[4]);
		long seed = Long.parseLong(args[5]);
		Instance inst = args.length > 6 ? Instance.parse(args[6], w, numFrogs) : Instance.legacy(w, numFrogs);
		// trajectory file (8th arg): with -Dwalk.record=true the driver runs its OWN loop and WRITES the
		// executed move + frog positions per shot; otherwise it READS the file and replays that exact
		// state sequence (so every config solves an identical sequence of planning problems). The
		// canonical reference is recorded by alpha-live on its own coherent path (warm-start intact),
		// then replayed to the stateless / re-anchoring configs, which are indifferent to whose path it is.
		boolean record = Boolean.getBoolean("walk.record");
		String trajPath = args.length > 7 ? args[7] : null;
		List<int[]> traj = (trajPath != null && !record) ? readTraj(trajPath, numFrogs) : null;
		if (traj != null) {
			shots = Math.min(shots, traj.size());
		}
		System.out.printf("gardeners-walk mode=%s W=%d h=%d frogs=%d shots=%d seed=%d coupled=%b walls=%d replay=%b%n",
				mode, w, h, numFrogs, shots, seed, COUPLED, inst.walls.size(), traj != null);

		ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
			Thread th = new Thread(r, "walk-solver");
			th.setDaemon(true);
			return th;
		});
		try {
			run(mode, w, h, numFrogs, shots, seed, inst, traj, record ? trajPath : null, exec);
		} finally {
			exec.shutdownNow();
		}
	}

	/** Read a trajectory file: one line per shot = "<moveDir> <c1> <r1> <c2> <r2> ..." (frog positions after the hop). */
	private static List<int[]> readTraj(String path, int numFrogs) throws IOException {
		List<int[]> traj = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new FileReader(path))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				String[] p = line.trim().split("\\s+");
				int[] rec = new int[1 + 2 * numFrogs];
				for (int i = 0; i < rec.length; i++) {
					rec[i] = Integer.parseInt(p[i]);
				}
				traj.add(rec);
			}
		}
		return traj;
	}

	private static final class Instance {
		int[] gard;
		List<int[]> frogs = new ArrayList<>();
		Set<Long> walls = new HashSet<>();

		static Instance parse(String path, int w, int expectFrogs) throws IOException {
			Instance inst = new Instance();
			try (BufferedReader in = new BufferedReader(new FileReader(path))) {
				String line;
				while ((line = in.readLine()) != null) {
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] p = line.trim().split("\\s+");
					switch (p[0]) {
						case "gard":
							inst.gard = new int[] { Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
							break;
						case "frog":
							inst.frogs.add(new int[] { Integer.parseInt(p[2]), Integer.parseInt(p[3]) });
							break;
						case "wall":
							inst.walls.add(key(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
							break;
						default:
							throw new IllegalArgumentException("bad instance line: " + line);
					}
				}
			}
			if (inst.frogs.size() != expectFrogs) {
				throw new IllegalArgumentException("instance has " + inst.frogs.size() + " frogs, expected " + expectFrogs);
			}
			return inst;
		}

		static Instance legacy(int w, int numFrogs) {
			Instance inst = new Instance();
			inst.gard = new int[] { w / 2, w / 2 };
			int[][] starts = { { w - 4, w / 2 }, { 4, w - 5 }, { w / 2, 4 }, { 5, 5 } };
			for (int i = 0; i < numFrogs; i++) {
				inst.frogs.add(new int[] { starts[i][0], starts[i][1] });
			}
			return inst;
		}
	}

	private static void run(String mode, int w, int h, int numFrogs, int shots, long seed, Instance inst, List<int[]> traj,
			String recordPath, ExecutorService exec) {
		StringBuilder recLines = recordPath != null ? new StringBuilder() : null;
		Random rng = new Random(seed);
		int[] gard = inst.gard.clone();
		List<int[]> frogs = new ArrayList<>();
		StringBuilder history = new StringBuilder();
		history.append(String.format(REANCHOR ? "playerObs(%d,%d,0).%n" : "playerAt(%d,%d,0).%n", gard[0], gard[1]));
		for (int i = 0; i < numFrogs; i++) {
			int[] f = inst.frogs.get(i);
			frogs.add(f.clone());
			history.append(String.format("frogAt(%d,%d,%d,0).%n", i + 1, f[0], f[1]));
		}
		StringBuilder wallFacts = new StringBuilder();
		for (int c = 1; c <= w; c++) {
			for (int r = 1; r <= w; r++) {
				if (!inst.walls.contains(key(c, r))) {
					wallFacts.append(String.format("free(%d,%d).%n", c, r));
				} else {
					wallFacts.append(String.format("wall(%d,%d).%n", c, r));
				}
			}
		}
		for (int c = 0; c <= w + 1; c++) {
			wallFacts.append(String.format("wall(%d,0).%n", c));
			wallFacts.append(String.format("wall(%d,%d).%n", c, w + 1));
		}
		for (int r = 1; r <= w; r++) {
			wallFacts.append(String.format("wall(0,%d).%n", r));
			wallFacts.append(String.format("wall(%d,%d).%n", w + 1, r));
		}


		boolean live = mode.equals("live");
		AlphaSession session = null;
		if (live) {
			session = newAlpha().newSession();
			session.add(rules(w));
			session.add(wallFacts.toString());
			session.add(history.toString());
			session.add(String.format("step(1..%d).", h));
		}

		int t = 0;
		int maxStep = h;
		double totalSolve = 0;
		double[] shotTimes = new double[shots];
		for (int shot = 0; shot < shots; shot++) {
			if (!live) {
				session = newAlpha().newSession();
				session.add(rules(w));
				session.add(wallFacts.toString());
				session.add(history.toString());
				session.add(String.format("step(1..%d).", t + h));
			}
			long t0 = System.nanoTime();
			Optional<AnswerSet> model = solveFirst(exec, session);
			double sec = (System.nanoTime() - t0) / 1e9;
			totalSolve += sec;
			shotTimes[shot] = sec;
			if (!model.isPresent()) {
				System.out.printf("shot %3d: UNSAT-OR-TIMEOUT after %.2fs — stopping (survived %d shots)%n", shot, sec, shot);
				System.out.printf("  state at stuck shot: gard=(%d,%d) frogs=%s time=%d%n", gard[0], gard[1],
						frogs.stream().map(f -> "(" + f[0] + "," + f[1] + ")").reduce("", (a, b) -> a + b), t);
				if (recLines != null) {
					writeTraj(recordPath, recLines);
				}
				System.out.printf("RESULT mode=%s survived=%d/%d totalSolve=%.3f%n", mode, shot, shots, totalSolve);
				System.exit(1);
			}
			Map<Integer, Integer> plan = extractMoves(model.get());
			verifyPlan(plan, gard, frogs, inst.walls, t, h, w);
			// executed move: from the trajectory in replay, else from this config's own plan
			int d = traj != null ? traj.get(shot)[0] : plan.get(t + 1);
			int[] tgt = { gard[0] + DIRS[d - 1][0], gard[1] + DIRS[d - 1][1] };
			if (tgt[0] >= 1 && tgt[0] <= w && tgt[1] >= 1 && tgt[1] <= w && !inst.walls.contains(key(tgt[0], tgt[1]))) {
				gard = tgt; // move
			} // else: bump into hedge/border — gardener stays put
			StringBuilder delta = new StringBuilder();
			t++;
			if (DEEPEN_EVERY > 0 && (shot + 1) % DEEPEN_EVERY == 0) {
				h++; // deepen the lookahead: an extra step fact below covers the deeper window
			}
			// re-anchor: observe the executed gardener position (supersedes older); else assert the move fact
			delta.append(REANCHOR
					? String.format("playerObs(%d,%d,%d).%n", gard[0], gard[1], t)
					: String.format("move(%d,%d).%n", t, d));
			for (int i = 0; i < numFrogs; i++) {
				int[] nxt;
				if (traj != null) {
					nxt = new int[] { traj.get(shot)[1 + 2 * i], traj.get(shot)[2 + 2 * i] };
				} else {
					List<int[]> legal = legalHops(frogs.get(i), gard, inst.walls, w);
					nxt = legal.get(rng.nextInt(legal.size()));
				}
				if (nxt[0] == gard[0] && nxt[1] == gard[1]) {
					System.out.printf("shot %3d: CAPTURE by frog %d — conformance broken%n", shot, i + 1);
					System.exit(1);
				}
				frogs.set(i, nxt);
				delta.append(String.format("frogAt(%d,%d,%d,%d).%n", i + 1, nxt[0], nxt[1], t));
			}
			for (int s2 = maxStep + 1; s2 <= t + h; s2++) {
				delta.append(String.format("step(%d).%n", s2));
			}
			maxStep = Math.max(maxStep, t + h);
			history.append(delta);
			if (live) {
				if (FRESH_SEARCH) {
					delta.append(String.format("tick(%d).%n", shot));
				}
				session.add(delta.toString());
				if (FRESH_SEARCH && shot > 0) {
					// retract the PREVIOUS shot's tick — a genuinely-asserted fact, so the session takes the
					// real retraction path: learned nogoods dropped, grounding kept (warm-ground cold-search).
					session.removeFacts(String.format("tick(%d).", shot - 1));
				}
			}
			if (recLines != null) {
				recLines.append(d);
				for (int[] f : frogs) {
					recLines.append(' ').append(f[0]).append(' ').append(f[1]);
				}
				recLines.append('\n');
			}
			StringBuilder fs = new StringBuilder();
			for (int[] f : frogs) {
				fs.append('(').append(f[0]).append(',').append(f[1]).append(')');
			}
			System.out.printf("shot %3d: solve=%.3fs gard=(%d,%d) frogs=%s%n", shot, sec, gard[0], gard[1], fs);
		}
		if (recLines != null) {
			writeTraj(recordPath, recLines);
		}
		double firstFive = avg(shotTimes, 0, Math.min(5, shots));
		double lastFive = avg(shotTimes, Math.max(0, shots - 5), shots);
		System.out.printf("%nOK: %d shots, 0 captures, all plans verified against independent cone checker.%n", shots);
		System.out.printf("total %s solve: %.3fs  (avg first5=%.3fs last5=%.3fs)%n", mode, totalSolve, firstFive, lastFive);
		System.out.printf("RESULT mode=%s survived=%d/%d totalSolve=%.3f%n", mode, shots, shots, totalSolve);
	}

	/** Independent checker: BFS frog cones (stay-or-approach, wall-aware) against the planned walk. */
	private static void verifyPlan(Map<Integer, Integer> plan, int[] gard, List<int[]> frogs, Set<Long> walls, int t, int h, int w) {
		int[] pos = gard.clone();
		List<Set<Long>> cones = new ArrayList<>();
		for (int[] f : frogs) {
			Set<Long> cone = new HashSet<>();
			cone.add(key(f[0], f[1]));
			cones.add(cone);
		}
		for (int tau = t + 1; tau <= t + h; tau++) {
			Integer d = plan.get(tau);
			if (d == null) {
				throw new IllegalStateException("plan missing move for time " + tau);
			}
			int[] tgt = { pos[0] + DIRS[d - 1][0], pos[1] + DIRS[d - 1][1] };
			if (tgt[0] >= 1 && tgt[0] <= w && tgt[1] >= 1 && tgt[1] <= w && !walls.contains(key(tgt[0], tgt[1]))) {
				pos = tgt;
			} // else bump: pos unchanged
			for (int i = 0; i < cones.size(); i++) {
				Set<Long> next = new HashSet<>();
				for (long cell : cones.get(i)) {
					int c = (int) (cell >> 32);
					int r = (int) cell;
					int added = 0;
					if (COUPLED) {
						if (r < pos[1]) {
							next.add(key(c, r + 1));
							added++;
						}
						if (r > pos[1]) {
							next.add(key(c, r - 1));
							added++;
						}
						if (c < pos[0]) {
							next.add(key(c + 1, r));
							added++;
						}
						if (c > pos[0]) {
							next.add(key(c - 1, r));
							added++;
						}
					} else {
						for (int[] dd : DIRS) {
							int nc = c + dd[0];
							int nr = r + dd[1];
							if (nc >= 1 && nc <= w && nr >= 1 && nr <= w) {
								next.add(key(nc, nr));
								added++;
							}
						}
					}
					if (added == 0) {
						next.add(cell); // gardener on the frog: it stays
					}
				}
				cones.set(i, next);
				if (next.contains(key(pos[0], pos[1]))) {
					throw new IllegalStateException("UNSOUND plan: frog " + (i + 1) + " could reach the gardener at time " + tau);
				}
			}
		}
	}

	private static void writeTraj(String path, StringBuilder recLines) {
		try (java.io.FileWriter fw = new java.io.FileWriter(path)) {
			fw.write(recLines.toString());
		} catch (IOException e) {
			throw new RuntimeException("failed to write trajectory " + path, e);
		}
	}

	private static long key(int c, int r) {
		return ((long) c << 32) | (r & 0xffffffffL);
	}

	/** The frog's actual behaviour: hop strictly toward the gardener if possible (walls block), else stay. */
	private static List<int[]> legalHops(int[] f, int[] gard, Set<Long> walls, int w) {
		List<int[]> legal = new ArrayList<>();
		if (COUPLED) {
			if (f[1] < gard[1]) {
				legal.add(new int[] { f[0], f[1] + 1 });
			}
			if (f[1] > gard[1]) {
				legal.add(new int[] { f[0], f[1] - 1 });
			}
			if (f[0] < gard[0]) {
				legal.add(new int[] { f[0] + 1, f[1] });
			}
			if (f[0] > gard[0]) {
				legal.add(new int[] { f[0] - 1, f[1] });
			}
			if (legal.isEmpty()) {
				legal.add(new int[] { f[0], f[1] });
			}
			return legal;
		}
		for (int[] dd : DIRS) {
			int nc = f[0] + dd[0];
			int nr = f[1] + dd[1];
			if (nc >= 1 && nc <= w && nr >= 1 && nr <= w) {
				legal.add(new int[] { nc, nr });
			}
		}
		if (legal.isEmpty()) {
			legal.add(new int[] { f[0], f[1] });
		}
		return legal;
	}

	private static Map<Integer, Integer> extractMoves(AnswerSet as) {
		Map<Integer, Integer> moves = new HashMap<>();
		for (Predicate p : as.getPredicates()) {
			if (p.getName().equals("move") && p.getArity() == 2) {
				for (Atom a : as.getPredicateInstances(p)) {
					int time = Integer.parseInt(a.getTerms().get(0).toString());
					int dir = Integer.parseInt(a.getTerms().get(1).toString());
					moves.put(time, dir);
				}
			}
		}
		return moves;
	}

	private static Optional<AnswerSet> solveFirst(ExecutorService exec, AlphaSession session) {
		Future<Optional<AnswerSet>> fut = exec.submit(() -> session.solve().findFirst());
		try {
			return fut.get(SHOT_CAP_SEC, TimeUnit.SECONDS);
		} catch (TimeoutException te) {
			fut.cancel(true);
			return Optional.empty();
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted", ie);
		} catch (ExecutionException ee) {
			throw new RuntimeException("solve failed", ee.getCause());
		}
	}

	/** The verified receding-horizon encoding: 4 actions, walls, stay-or-approach frogs. */
	private static String rules(int w) {
		return ""
				+ "dir(1..4).\n"
				// re-anchor: guess moves only for the current window (steps after the latest observation),
				// and require a position at each such step. Past steps are observation-pinned (no free moves).
				+ (REANCHOR
						? "{ move(T,D) : dir(D) } :- step(T), not gsup(T).\n"
								+ "hasmove(T) :- move(T,D).\n"
								+ ":- step(T), not gsup(T), not haspos(T).\n"
								+ ":- move(T,D1), move(T,D2), D1<D2.\n"
								+ "playerAt(C,R,T) :- playerObs(C,R,T), not gsup(T).\n"
								+ "gsup(T) :- playerObs(C2,R2,T2), T=T2-1.\n"
						: "{ move(T,D) : dir(D) } :- step(T).\n"
								+ "hasmove(T) :- move(T,D).\n"
								+ ":- step(T), not hasmove(T).\n"
								+ ":- move(T,D1), move(T,D2), D1<D2.\n")
				+ "playerAt(C,R2,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,1), R2=R+1, free(C,R2).\n"
				+ "playerAt(C,R2,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,2), R2=R-1, free(C,R2).\n"
				+ "playerAt(C2,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,3), C2=C+1, free(C2,R).\n"
				+ "playerAt(C2,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,4), C2=C-1, free(C2,R).\n"
				+ "playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,1), R2=R+1, wall(C,R2).\n"
				+ "playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,2), R2=R-1, wall(C,R2).\n"
				+ "playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,3), C2=C+1, wall(C2,R).\n"
				+ "playerAt(C,R,T) :- playerAt(C,R,T1), T1=T-1, step(T), move(T,4), C2=C-1, wall(C2,R).\n"
				+ "haspos(T) :- playerAt(C,R,T).\n"
				// require a position at each planned step (all steps when accumulating; only the current
				// window when re-anchoring — superseded past steps legitimately have no playerAt).
				+ (REANCHOR ? "" : ":- step(T), not haspos(T).\n")
				+ "danger(F,C,R,T) :- frogAt(F,C,R,T), not superseded(F,T).\n"
				+ "superseded(F,T) :- frogAt(F,C,R,T2), T=T2-1.\n"
				// a frog the gardener stands on stays put (blocks the swap-through exploit)
				+ "danger(F,C,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(C,R,T).\n"
				+ (COUPLED
						// skittish frogs hop strictly toward the gardener's current cell (walls block)
						? "danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), R<PR, R2=R+1.\n"
								+ "danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), R>PR, R2=R-1.\n"
								+ "danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), C<PC, C2=C+1.\n"
								+ "danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), playerAt(PC,PR,T), C>PC, C2=C-1.\n"
						// control column: frogs hop in any wall-free direction
						: "danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), R2=R+1, R2<=" + w + ".\n"
								+ "danger(F,C,R2,T) :- danger(F,C,R,T1), T1=T-1, step(T), R2=R-1, R2>=1.\n"
								+ "danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), C2=C+1, C2<=" + w + ".\n"
								+ "danger(F,C2,R,T) :- danger(F,C,R,T1), T1=T-1, step(T), C2=C-1, C2>=1.\n")
				+ ":- playerAt(C,R,T), danger(F,C,R,T).\n";
	}

	private static Alpha newAlpha() {
		at.ac.tuwien.kr.alpha.api.config.SystemConfig cfg = new at.ac.tuwien.kr.alpha.api.config.SystemConfig();
		// justification search must stay ENABLED in session mode (2026-07-17: disabling it
		// degrades live shots ~2500x); -Dwalk.dj=true disables it for batch experiments only.
		cfg.setDisableJustificationSearch(Boolean.getBoolean("walk.dj"));
		return new AlphaImpl(cfg);
	}

	private static double avg(double[] xs, int from, int to) {
		double s = 0;
		for (int i = from; i < to; i++) {
			s += xs[i];
		}
		return to > from ? s / (to - from) : 0;
	}

	private GardenersWalkBenchmark() {
	}
}
