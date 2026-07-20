package at.ac.tuwien.kr.alpha.app.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * Incremental graph 5-colouring under a <em>mixed</em> edit stream — the search-hard L&amp;W 2018
 * benchmark ("no grounding problem but requires efficient search"), driven through every kind of
 * between-shot edit at once so it exercises the full incremental machinery (fact addition, fact
 * <em>retraction</em>, and constraint addition) on a single instance.
 *
 * <p><b>Rotating protocol.</b> After the base solve (shot 1), each subsequent shot applies exactly one
 * edit, cycling through four operation types:
 * <ol>
 *   <li><b>grow</b> — add a new pendant vertex plus one edge to a random existing vertex
 *       ({@code v(n). e(nb,n).});</li>
 *   <li><b>add-edge</b> — add one edge between two existing vertices ({@code e(a,b).});</li>
 *   <li><b>retract</b> — remove one currently-present edge ({@code removeFacts("e(a,b).")});</li>
 *   <li><b>constrain</b> — forbid the colour the current model assigns to some vertex
 *       ({@code :- cK(v).}), a "user rejects this colour, recolour" edit.</li>
 * </ol>
 * If a shot's intended operation is inapplicable (e.g. retract when no edges remain, or the graph is
 * already complete), it falls back to <b>grow</b>, so every shot performs real work. Growth keeps the
 * instance broadly satisfiable while add-edge/constrain push it toward conflict; the run stops at the
 * first UNSAT shot (no model to read a colour from for the next constrain edit).
 *
 * <p><b>Fair comparison.</b> The retract target and the forbidden colour are model/RNG-dependent, so the
 * two modes must not each generate their own edit stream. This driver runs both in one process: the
 * {@code live} session produces the exact edit sequence (recorded as concrete fact/constraint deltas),
 * and {@code batch} <em>replays the identical sequence</em>, rebuilding a fresh session from the current
 * vertex/edge/constraint state at every shot. Both therefore solve the exact same program each shot;
 * only the {@code solve}-first call is timed (matching the other incremental benchmarks).
 *
 * <p><b>Per-shot timeout.</b> Every {@code solve} runs under a wall-clock cap ({@code perShotTimeoutSec},
 * default {@value #DEFAULT_TIMEOUT_SEC}s, matching the L&amp;W Table-3 timeout). A solve that exceeds the cap
 * is aborted (the solver honours a cooperative interrupt) and recorded as a <b>TIMEOUT</b> rather than
 * hanging the run — the dense degree-8 instances near the colourability phase transition can push Alpha's
 * search into multi-minute (UNSAT-decision) blow-ups. Because a timed-out {@code live} solve leaves the
 * incremental session mid-search, the {@code live} sequence stops at its first timeout; {@code batch} shots
 * are independent (fresh session each), so a batch timeout only marks that shot and the replay continues.
 *
 * Usage:
 *   IncrementalColoringBenchmark &lt;numVertices&gt; &lt;numEdges&gt; &lt;maxShots&gt; [seed] [perShotTimeoutSec]
 */
public final class IncrementalColoringBenchmark {

	private static final int COLORS = 5;
	/** Default per-solve wall-clock cap in seconds (matches the L&amp;W Table-3 timeout). */
	private static final int DEFAULT_TIMEOUT_SEC = 300;
	/** Colour predicates {@code c1..c5}. */
	private static final String[] COLOR_PREDS = {"c1", "c2", "c3", "c4", "c5"};
	/**
	 * Edit rotation ({@code -Dcoloring.rotation=...}): {@code mix} (default) cycles grow / add-edge / retract /
	 * constrain; {@code grow} is pure pendant growth (monotone, never invalidates the current colouring);
	 * {@code monotone} alternates grow / retract (both non-invalidating); {@code noconstrain} cycles grow /
	 * add-edge / retract. The non-{@code mix} modes avoid the model-invalidating edits (add-edge, constrain)
	 * that push the warm incremental search onto the colourability phase-transition cliff at large |V|.
	 */
	private static final String ROTATION = System.getProperty("coloring.rotation", "mix");

	/**
	 * {@code -Dcoloring.growPerShot=k} (default 1): how many pendant (vertex + edge) pairs a single grow
	 * shot adds before the one timed solve. Lets us vary the per-shot increment size in the monotone
	 * {@code grow} protocol while holding the shot count fixed (the coloring analog of
	 * {@code -Dreach.edgesPerShot}). Only affects grow-slot shots; other rotation slots are unchanged.
	 */
	private static final int GROW_PER_SHOT = Math.max(1, Integer.getInteger("coloring.growPerShot", 1));

	public static void main(String[] args) {
		if (args.length < 3 || args.length > 5) {
			System.err.println("Usage: IncrementalColoringBenchmark <numVertices> <numEdges> <maxShots> [seed] [perShotTimeoutSec]");
			System.exit(2);
		}
		int numVertices = Integer.parseInt(args[0]);
		int numEdges = Integer.parseInt(args[1]);
		int maxShots = Integer.parseInt(args[2]);
		long seed = args.length >= 4 ? Long.parseLong(args[3]) : 42L;
		int timeoutSec = args.length == 5 ? Integer.parseInt(args[4]) : DEFAULT_TIMEOUT_SEC;

		String rules = colorRules();
		Set<String> baseEdgeKeys = new LinkedHashSet<>();
		Set<String> baseFacts = buildBaseFacts(numVertices, numEdges, seed, baseEdgeKeys);
		String baseFactsStr = String.join("", baseFacts);

		// Daemon workers so an aborted (interrupted-but-slow-to-notice) solve never blocks JVM exit; a
		// cached pool so a still-winding-down worker never blocks the next solve's submission.
		ExecutorService exec = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "coloring-solve");
			t.setDaemon(true);
			return t;
		});

		Alpha alpha = newAlpha();
		// Warm the coloring solve path (timed live phase runs first, so don't let it pay cold JIT). Bounded
		// by the same cap so a pathological base does not hang warm-up.
		for (int w = 0; w < 2; w++) {
			AlphaSession warm = alpha.newSession();
			warm.add(rules);
			warm.add(baseFactsStr);
			solveWithTimeout(exec, warm, timeoutSec);
		}

		System.out.printf("%n|V|=%d  |E|=%d  colors=%d  maxShots=%d  seed=%d  perShotTimeout=%ds  (rotating: grow / add-edge / retract / constrain)%n",
				numVertices, numEdges, COLORS, maxShots, seed, timeoutSec);
		System.out.println("(per-shot times printed live as they complete)");
		System.out.flush();

		// ---- live: one session, record the exact edit stream. ----
		List<Op> ops = new ArrayList<>();
		List<Integer> liveSat = new ArrayList<>();  // per shot: 1=SAT, 0=UNSAT, -1=TIMEOUT
		List<int[]> stats = new ArrayList<>();      // per shot: {|V|, |E|, #constraints}
		List<String> opLabels = new ArrayList<>();
		double[] liveTimes = runLive(alpha, exec, timeoutSec, rules, baseFactsStr, numVertices, baseEdgeKeys, maxShots, seed,
				ops, liveSat, stats, opLabels);

		// ---- batch: replay the identical edit stream, rebuilding each shot. ----
		List<Boolean> batchTimedOut = new ArrayList<>();
		double[] batchTimes = runBatchReplay(alpha, exec, timeoutSec, rules, baseFacts, ops, liveTimes.length, batchTimedOut);

		exec.shutdownNow();

		double liveTotal = 0, batchTotal = 0;
		int liveTimeouts = 0, batchTimeouts = 0;
		System.out.printf("%n%-6s | %-10s | %-6s | %-6s | %-7s | %-12s | %-12s%n",
				"shot", "op", "|V|", "|E|", "constr", "live (s)", "batch (s)");
		System.out.printf("%-6s-+-%-10s-+-%-6s-+-%-6s-+-%-7s-+-%-12s-+-%-12s%n",
				"------", "----------", "------", "------", "-------", "------------", "------------");
		for (int i = 0; i < liveTimes.length; i++) {
			int[] s = stats.get(i);
			liveTotal += liveTimes[i];
			batchTotal += batchTimes[i];
			String liveStat = liveSat.get(i) == -1 ? " TIMEOUT" : liveSat.get(i) == 0 ? " UNSAT" : "";
			String batchStat = batchTimedOut.get(i) ? " TIMEOUT" : "";
			if (liveSat.get(i) == -1) {
				liveTimeouts++;
			}
			if (batchTimedOut.get(i)) {
				batchTimeouts++;
			}
			System.out.printf("%-6d | %-10s | %-6d | %-6d | %-7d | %8.3f%-4s | %8.3f%-4s%n",
					i + 1, opLabels.get(i), s[0], s[1], s[2], liveTimes[i], liveStat, batchTimes[i], batchStat);
		}
		System.out.printf("%n  total live:  %.3fs over %d shots  (%d timeout%s)%n",
				liveTotal, liveTimes.length, liveTimeouts, liveTimeouts == 1 ? "" : "s");
		System.out.printf("  total batch: %.3fs over %d shots  (%d timeout%s)%n",
				batchTotal, liveTimes.length, batchTimeouts, batchTimeouts == 1 ? "" : "s");
		System.out.printf("  speedup (batch/live): %.2fx  %s%n", batchTotal / liveTotal,
				batchTotal > liveTotal ? "(live faster)" : "(live SLOWER)");
		if (liveTimeouts > 0 || batchTimeouts > 0) {
			System.out.println("  NOTE: totals/speedup include per-shot timeouts capped at "
					+ timeoutSec + "s; treat as lower bounds on the true (un-capped) cost.");
		}
	}

	/** Outcome of a single timed, timeout-bounded solve. */
	private static final class SolveResult {
		final Optional<AnswerSet> model;
		final double seconds;
		final boolean timedOut;

		SolveResult(Optional<AnswerSet> model, double seconds, boolean timedOut) {
			this.model = model;
			this.seconds = seconds;
			this.timedOut = timedOut;
		}
	}

	/**
	 * Solve {@code session} on a worker thread and wait at most {@code capSec} seconds. On timeout the
	 * worker is interrupted (the solver's cooperative check aborts the search) and the result is flagged
	 * {@code timedOut} with the elapsed (~cap) time; the caller decides whether the session is still usable.
	 */
	private static SolveResult solveWithTimeout(ExecutorService exec, AlphaSession session, int capSec) {
		long t0 = System.nanoTime();
		Future<Optional<AnswerSet>> fut = exec.submit(() -> session.solve().findFirst());
		try {
			Optional<AnswerSet> model = fut.get(capSec, TimeUnit.SECONDS);
			return new SolveResult(model, (System.nanoTime() - t0) / 1e9, false);
		} catch (TimeoutException te) {
			fut.cancel(true); // interrupt the worker; DefaultSolver honours the interrupt and aborts
			return new SolveResult(Optional.empty(), (System.nanoTime() - t0) / 1e9, true);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			fut.cancel(true);
			throw new RuntimeException("Benchmark thread interrupted", ie);
		} catch (ExecutionException ee) {
			throw new RuntimeException("Solve failed", ee.getCause());
		}
	}

	/** Live session: base solve, then one rotating edit per shot; records the edit stream + per-shot stats. */
	private static double[] runLive(Alpha alpha, ExecutorService exec, int capSec, String rules, String baseFactsStr,
			int numVertices, Set<String> baseEdgeKeys, int maxShots, long seed,
			List<Op> opsOut, List<Integer> satOut, List<int[]> statsOut, List<String> labelsOut) {
		AlphaSession session = alpha.newSession();
		session.add(rules);
		session.add(baseFactsStr);

		Random rnd = new Random(seed ^ 0x5eedL);
		int numV = numVertices;
		Set<String> edgeKeys = new LinkedHashSet<>(baseEdgeKeys);
		Set<String> forbidden = new LinkedHashSet<>();
		int constraintCount = 0;
		List<Double> times = new ArrayList<>();

		SolveResult r = solveWithTimeout(exec, session, capSec);
		Optional<AnswerSet> model = r.model;
		times.add(r.seconds);
		satOut.add(r.timedOut ? -1 : model.isPresent() ? 1 : 0);
		statsOut.add(new int[] {numV, edgeKeys.size(), constraintCount});
		labelsOut.add("base");
		System.out.printf("  live  shot %-2d [%-9s]: %8.3fs%s%n", 1, "base", r.seconds, liveMark(r));
		System.out.flush();
		if (r.timedOut) { // incremental session is mid-search and unusable — stop here
			System.out.println("  live: base solve timed out; stopping incremental sequence.");
			return toArray(times);
		}

		for (int shot = 2; shot <= maxShots; shot++) {
			int rot = rotForShot(shot);
			Op op = decideOp(rot, model.orElse(null), rnd, numV, edgeKeys, forbidden);
			// Commit the op's effect to the running graph/constraint state.
			numV = op.newNumV;
			edgeKeys.addAll(op.edgeKeysAdded);
			if (op.edgeKeyRemoved != null) {
				edgeKeys.remove(op.edgeKeyRemoved);
			}
			if (op.constraintAdded != null) {
				forbidden.add(op.forbiddenLit);
				constraintCount++;
			}
			// Apply to the live session.
			for (String f : op.factsAdded) {
				session.add(f);
			}
			if (op.factRemoved != null) {
				session.removeFacts(op.factRemoved);
			}
			if (op.constraintAdded != null) {
				session.add(op.constraintAdded);
			}
			opsOut.add(op);

			// Experiment: inject extra ground constraints from a file right before a chosen shot's solve,
			// to test whether specific nogoods are what a fresh solve has that the warm one lacks.
			String injectFile = System.getProperty("coloring.injectFile");
			if (injectFile != null && shot == Integer.getInteger("coloring.injectAtShot", -1)) {
				try {
					String cons = new String(Files.readAllBytes(Paths.get(injectFile)));
					session.add(cons);
					System.out.printf("  [injected constraints from %s before shot %d]%n", injectFile, shot);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			// Experiment: eagerly ground EVERY edge-colouring constraint for the current graph before this shot,
			// so the warm session's pruning-constraint set is provably COMPLETE (no path-dependent gap).
			if (Boolean.getBoolean("coloring.eagerEdgeConstraints")) {
				StringBuilder sb = new StringBuilder();
				for (String key : edgeKeys) {
					String[] ab = key.split(",");
					for (int k = 0; k < COLORS; k++) {
						String c = COLOR_PREDS[k];
						sb.append(":- e(").append(ab[0]).append(",").append(ab[1]).append("), ")
								.append(c).append("(").append(ab[0]).append("), ")
								.append(c).append("(").append(ab[1]).append(").\n");
					}
				}
				session.add(sb.toString());
				System.out.printf("  [eagerly added %d edge constraints before shot %d]%n", edgeKeys.size() * COLORS, shot);
			}

			r = solveWithTimeout(exec, session, capSec);
			model = r.model;
			times.add(r.seconds);
			satOut.add(r.timedOut ? -1 : model.isPresent() ? 1 : 0);
			statsOut.add(new int[] {numV, edgeKeys.size(), constraintCount});
			labelsOut.add(op.label);
			System.out.printf("  live  shot %-2d [%-9s]: %8.3fs%s%n", shot, op.label, r.seconds, liveMark(r));
			System.out.flush();
			if (r.timedOut) { // session corrupted by the aborted solve — stop the incremental sequence
				System.out.printf("  live: shot %d timed out; stopping incremental sequence.%n", shot);
				break;
			}
		}

		return toArray(times);
	}

	private static String liveMark(SolveResult r) {
		if (r.timedOut) {
			return "  TIMEOUT";
		}
		return r.model.isPresent() ? "" : "  UNSAT";
	}

	private static double[] toArray(List<Double> times) {
		double[] out = new double[times.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = times.get(i);
		}
		return out;
	}

	/** Batch: rebuild a fresh session each shot from rules + current facts + all constraints, solve-first. */
	private static double[] runBatchReplay(Alpha alpha, ExecutorService exec, int capSec, String rules,
			Set<String> baseFacts, List<Op> ops, int shots, List<Boolean> timedOutOut) {
		double[] times = new double[shots];
		Set<String> facts = new LinkedHashSet<>(baseFacts);
		List<String> constraints = new ArrayList<>();
		for (int shot = 1; shot <= shots; shot++) {
			if (shot >= 2) {
				Op op = ops.get(shot - 2);
				facts.addAll(op.factsAdded);
				if (op.factRemoved != null) {
					facts.remove(op.factRemoved);
				}
				if (op.constraintAdded != null) {
					constraints.add(op.constraintAdded);
				}
			}
			AlphaSession session = alpha.newSession();
			session.add(rules);
			session.add(String.join("", facts));
			if (!constraints.isEmpty()) {
				session.add(String.join("", constraints));
			}
			dumpShot(shot, rules + String.join("", facts) + String.join("", constraints));
			// Each batch shot is an independent fresh session, so a timeout only forfeits this shot; the
			// replay continues (unlike live, which cannot reuse a session left mid-search).
			SolveResult r = solveWithTimeout(exec, session, capSec);
			times[shot - 1] = r.seconds;
			timedOutOut.add(r.timedOut);
			System.out.printf("  batch shot %-2d: %8.3fs%s%n", shot, r.seconds, r.timedOut ? "  TIMEOUT" : "");
			System.out.flush();
		}
		return times;
	}

	/**
	 * Decide the edit for this shot given the rotation slot and current state. Returns an {@link Op}
	 * describing the concrete deltas; falls back to a grow edit whenever the intended edit is
	 * inapplicable, so every shot does real work. {@code model} is {@code null} when the previous shot
	 * was UNSAT — the run continues (each shot's UNSAT decision is still timed), but the model-driven
	 * {@code constrain} edit has no colour to forbid and so falls back to a structural grow.
	 */
	private static Op decideOp(int rot, AnswerSet model, Random rnd, int numV, Set<String> edgeKeys, Set<String> forbidden) {
		switch (rot) {
			case 1: { // add-edge between two existing vertices
				for (int tries = 0; tries < 50; tries++) {
					int a = 1 + rnd.nextInt(numV);
					int b = 1 + rnd.nextInt(numV);
					if (a == b) {
						continue;
					}
					int lo = Math.min(a, b), hi = Math.max(a, b);
					String key = lo + "," + hi;
					if (!edgeKeys.contains(key)) {
						return Op.addEdge(lo, hi, numV);
					}
				}
				return grow(numV, rnd);
			}
			case 2: { // retract an existing edge
				if (edgeKeys.isEmpty()) {
					return grow(numV, rnd);
				}
				List<String> keys = new ArrayList<>(edgeKeys);
				String key = keys.get(rnd.nextInt(keys.size()));
				return Op.retract(key, numV);
			}
			case 3: { // forbid the current colour of some vertex
				if (model == null) { // previous shot UNSAT — no colour to reject; do structural work instead
					return grow(numV, rnd);
				}
				Map<String, String> coloring = coloring(model);
				for (int tries = 0; tries < 50; tries++) {
					int v = 1 + rnd.nextInt(numV);
					String c = coloring.get(Integer.toString(v));
					if (c == null) {
						continue;
					}
					String lit = c + "(" + v + ")";
					if (!forbidden.contains(lit)) {
						return Op.constrain(lit, numV);
					}
				}
				return grow(numV, rnd);
			}
			default: // case 0: grow (GROW_PER_SHOT pendants per shot)
				return grow(numV, rnd, GROW_PER_SHOT);
		}
	}

	/** Map the 0-based edit index of a shot to a rotation slot (0 grow, 1 add-edge, 2 retract, 3 constrain). */
	private static int rotForShot(int shot) {
		int i = shot - 2;
		switch (ROTATION) {
			case "grow":       return 0;                    // pure pendant growth (monotone)
			case "monotone":   return (i % 2 == 0) ? 0 : 2; // grow / retract (both non-invalidating)
			case "noconstrain": return i % 3;               // grow / add-edge / retract
			default:           return i % 4;                // "mix": grow / add-edge / retract / constrain
		}
	}

	/** Single-pendant grow (used by the mixed-rotation fallbacks). */
	private static Op grow(int numV, Random rnd) {
		return grow(numV, rnd, 1);
	}

	/**
	 * Grow edit: add {@code g} new pendant vertices ({@code numV+1 .. numV+g}), each linked to a random
	 * pre-existing vertex. Monotone (pendants are always colourable), so it never invalidates the current
	 * colouring regardless of {@code g}. {@code g = 1} is the original single-pendant grow.
	 */
	private static Op grow(int numV, Random rnd, int g) {
		List<String> facts = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		for (int i = 1; i <= g; i++) {
			int nv = numV + i;
			int nb = 1 + rnd.nextInt(numV); // attach to a random pre-existing vertex
			facts.add("v(" + nv + ").\n");
			facts.add(edgeFact(nb, nv));
			keys.add(nb + "," + nv);
		}
		return new Op("grow", facts, null, keys, null, null, null, numV + g);
	}

	/** Map each vertex (its term, as a string) to the colour predicate {@code c1..c5} it currently holds. */
	private static Map<String, String> coloring(AnswerSet as) {
		Set<String> colorNames = new LinkedHashSet<>();
		for (String c : COLOR_PREDS) {
			colorNames.add(c);
		}
		Map<String, String> m = new HashMap<>();
		for (Predicate p : as.getPredicates()) {
			if (p.getArity() == 1 && colorNames.contains(p.getName())) {
				for (Atom a : as.getPredicateInstances(p)) {
					m.put(a.getTerms().get(0).toString(), p.getName());
				}
			}
		}
		return m;
	}

	private static String edgeFact(int lo, int hi) {
		return "e(" + lo + "," + hi + ").\n";
	}

	private static final String DUMP_DIR = System.getProperty("clingoDumpDir");

	/** With -DclingoDumpDir=&lt;dir&gt;, write each batch shot's full program so an external clingo can solve the identical state. */
	private static void dumpShot(int shot, String program) {
		if (DUMP_DIR == null) {
			return;
		}
		try {
			Path dir = Paths.get(DUMP_DIR);
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(String.format("shot-%03d.lp", shot)), program);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Base graph facts (vertices + random edges); also fills {@code edgeKeysOut} with the "lo,hi" keys. */
	private static Set<String> buildBaseFacts(int numVertices, int numEdges, long seed, Set<String> edgeKeysOut) {
		Set<String> facts = new LinkedHashSet<>();
		for (int v = 1; v <= numVertices; v++) {
			facts.add("v(" + v + ").\n");
		}
		Random rnd = new Random(seed);
		int guard = 0;
		while (edgeKeysOut.size() < numEdges && guard < numEdges * 50 + 1000) {
			guard++;
			int a = 1 + rnd.nextInt(numVertices);
			int b = 1 + rnd.nextInt(numVertices);
			if (a == b) {
				continue;
			}
			int lo = Math.min(a, b), hi = Math.max(a, b);
			if (edgeKeysOut.add(lo + "," + hi)) {
				facts.add(edgeFact(lo, hi));
			}
		}
		return facts;
	}

	/**
	 * 5-colouring encoding in the Alpha-friendly one-predicate-per-colour idiom (cf.
	 * {@code examples/3col_with_tests.asp} and L&amp;W 2018): each colour is guessed via mutual negation
	 * over the other four (encoding "exactly one colour" directly in the guess), and a proper-colouring
	 * constraint forbids equal colours across every edge. Both are graph-independent (quantified over
	 * {@code v/1} and {@code e/2}), so they form the fixed rule base while the graph streams in as facts.
	 */
	private static String colorRules() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < COLORS; i++) {
			sb.append(COLOR_PREDS[i]).append("(V) :- v(V)");
			for (int j = 0; j < COLORS; j++) {
				if (j != i) {
					sb.append(", not ").append(COLOR_PREDS[j]).append("(V)");
				}
			}
			sb.append(".\n");
		}
		for (int i = 0; i < COLORS; i++) {
			sb.append(":- e(V,W), ").append(COLOR_PREDS[i]).append("(V), ").append(COLOR_PREDS[i]).append("(W).\n");
		}
		return sb.toString();
	}

	/** One recorded edit: concrete fact/constraint deltas so {@code batch} can replay {@code live} exactly. */
	private static final class Op {
		final String label;
		final List<String> factsAdded;    // facts to add this shot (may be empty)
		final String factRemoved;         // edge fact to remove, or null
		final List<String> edgeKeysAdded; // "lo,hi" keys added to the edge set (may be empty; >1 for multi-grow)
		final String edgeKeyRemoved;      // "lo,hi" removed from the edge set, or null
		final String constraintAdded;     // constraint text to add, or null
		final String forbiddenLit;        // "cK(v)" forbidden by the constraint, or null
		final int newNumV;                // vertex count after this edit

		Op(String label, List<String> factsAdded, String factRemoved, List<String> edgeKeysAdded,
				String edgeKeyRemoved, String constraintAdded, String forbiddenLit, int newNumV) {
			this.label = label;
			this.factsAdded = factsAdded;
			this.factRemoved = factRemoved;
			this.edgeKeysAdded = edgeKeysAdded;
			this.edgeKeyRemoved = edgeKeyRemoved;
			this.constraintAdded = constraintAdded;
			this.forbiddenLit = forbiddenLit;
			this.newNumV = newNumV;
		}

		static Op addEdge(int lo, int hi, int numV) {
			List<String> facts = new ArrayList<>();
			facts.add(edgeFact(lo, hi));
			return new Op("add-edge", facts, null, List.of(lo + "," + hi), null, null, null, numV);
		}

		static Op retract(String key, int numV) {
			String[] ab = key.split(",");
			String fact = edgeFact(Integer.parseInt(ab[0]), Integer.parseInt(ab[1]));
			return new Op("retract", new ArrayList<>(), fact, List.of(), key, null, null, numV);
		}

		static Op constrain(String lit, int numV) {
			return new Op("constrain", new ArrayList<>(), null, List.of(), null, ":- " + lit + ".\n", lit, numV);
		}
	}

	private static Alpha newAlpha() {
		at.ac.tuwien.kr.alpha.api.config.SystemConfig cfg = new at.ac.tuwien.kr.alpha.api.config.SystemConfig();
		if (Boolean.getBoolean("coloring.disableJustifications")) {
			cfg.setDisableJustificationSearch(true);
		}
		String heuristic = System.getProperty("bench.heuristic");
		if (heuristic != null) {
			cfg.setBranchingHeuristicName(heuristic);
		}
		return new AlphaImpl(cfg);
	}

	private IncrementalColoringBenchmark() {
	}
}
