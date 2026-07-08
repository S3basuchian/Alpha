package at.ac.tuwien.kr.alpha.app.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;

/**
 * Ground-explosion benchmark in the <em>constraint-streaming</em> shape: the {@code dom/1} universe is
 * fixed and complete from shot 1 (so shot 1 is identical to the non-incremental L&amp;W 2018 Example 1),
 * and each subsequent shot adds <em>constraints</em> that forbid answer sets containing particular
 * {@code p/6} cross-product atoms — {@code :- p(1,1,1,1,1,1).}, then {@code :- p(2,2,2,2,2,2).}, etc.,
 * potentially several per shot.
 *
 * <p>This is the dual of a universe-streaming variant: rather than growing the {@code dom/1} universe,
 * the universe (hence the |dom|⁶ candidate ground space that explodes eager grounders) is fixed, and we
 * stream <em>restrictions</em>. It is the canonical multi-shot "solve, then block solutions and
 * re-solve" loop (blocking-clause enumeration / iterative refinement).
 *
 * <p><b>Why bounded enumeration, not first-answer-set.</b> The encoding's answer sets are: one
 * "nothing selected" set (always present, derives no {@code p}) plus one set per chosen domain element
 * {@code X} (derives exactly {@code p(X,…,X)}). A {@code :- p(i,…,i).} constraint removes precisely the
 * answer set that selects {@code i}; the empty-selection set always survives, so the program never goes
 * UNSAT. "First answer set" would keep returning the trivial empty-selection set and the constraints
 * would never bite — so each shot enumerates up to {@code maxAnswerSets} answer sets (default 10), a
 * fixed, bounded per-shot enumeration target. This makes every solver in the suite — live Alpha, batch
 * Alpha, rebuilt clingo, multi-shot clingo — run the same "give me up to N answer sets" task per shot,
 * and the printed count is {@code min(available, maxAnswerSets)}: it stays pinned at the cap while more
 * than {@code maxAnswerSets} selections survive, then drops as the streamed constraints exhaust them.
 *
 * <p><b>What it exercises.</b> Session mode keeps grounder + atom store + learned nogoods + VSIDS across
 * shots; because each shot fully enumerates, it also drives the between-shot enumeration-nogood purge
 * ({@code NoGoodStore.purgeEnumerationNoGoods()}) and the soundness of adding a constraint after a
 * full-enumeration solve. Batch mode rebuilds a fresh session each shot (encoding + full dom + all
 * constraints so far) and re-enumerates from cold.
 *
 * <p>The {@code answer sets} column is printed for both modes; the two columns must agree shot-for-shot
 * — it doubles as a correctness check on the incremental machinery for this access pattern.
 *
 * Usage:
 *   IncrementalGroundExplosionConstraintBenchmark &lt;encoding.lp&gt; &lt;dom.lp&gt; &lt;numShots&gt; [live|batch] [forbidPerShot] [maxAnswerSets] [asc|desc|scatter] [forbidTotal] [forbidOrderFile]
 *
 * numShots counts shot 1 (the unconstrained base) plus (numShots-1) constraint shots. Each constraint
 * shot forbids the next {@code forbidPerShot} (default 1) domain elements, capped so the run forbids at
 * most {@code forbidTotal} elements in total (default |dom|, i.e. no extra cap). The cap lets a caller
 * deliberately leave a fraction of the domain unconstrained: with {@code forbidTotal = round(0.9*|dom|)}
 * roughly 10% of the elements never receive a forbidding constraint, and the last constraint shot forbids
 * only the short remainder rather than a full {@code forbidPerShot} chunk. Each shot enumerates at most
 * {@code maxAnswerSets} (default 10) answer sets.
 */
public final class IncrementalGroundExplosionConstraintBenchmark {

	public static void main(String[] args) throws IOException {
		if (args.length < 2 || args.length > 9) {
			System.err.println("Usage: IncrementalGroundExplosionConstraintBenchmark <encoding.lp> <dom.lp> [<numShots>|paper] [live|batch] [forbidPerShot] [maxAnswerSets] [asc|desc|scatter] [forbidTotal] [forbidOrderFile]");
			System.err.println("  Default (numShots omitted, or the literal 'paper'): reproduce the paper Table-1 halving X/Y forbid schedule for this |dom|.");
			System.exit(2);
		}
		Path encodingPath = Paths.get(args[0]);
		Path domPath = Paths.get(args[1]);
		// Paper mode: with numShots omitted or given as the literal "paper", the forbid schedule, shot count
		// and forbidTotal are all derived from |dom| per Table 1 (the halving X/Y schedule), so a bare
		// "<encoding> <dom>" run reproduces the paper row for that domain size.
		boolean paperMode = args.length < 3 || args[2].equalsIgnoreCase("paper");
		int numShots = paperMode ? -1 : Integer.parseInt(args[2]);
		String mode = args.length >= 4 ? args[3] : "live";
		// forbidPerShot is either a single integer (uniform: that many elements every constraint shot) or a
		// comma-separated schedule "n1,n2,n3" — constraint shot j forbids n_j elements, so the forbidding
		// rate can be front-loaded/decayed (e.g. "250,150,50"). Past the end of the schedule, shots forbid 0
		// (they just re-solve). The cumulative forbidTotal cap still applies.
		String forbidPerShotArg = args.length >= 5 ? args[4] : "1";
		int[] forbidSchedule = null;
		int forbidPerShot = 1;
		if (forbidPerShotArg.contains(",")) {
			String[] parts = forbidPerShotArg.split(",");
			forbidSchedule = new int[parts.length];
			for (int i = 0; i < parts.length; i++) {
				forbidSchedule[i] = Integer.parseInt(parts[i].trim());
			}
		} else {
			forbidPerShot = Integer.parseInt(forbidPerShotArg);
		}
		int maxAnswerSets = args.length >= 6 ? Integer.parseInt(args[5]) : 10;
		String forbidOrder = args.length >= 7 ? args[6] : (paperMode ? "scatter" : "asc");

		String encoding = Files.readString(encodingPath);
		List<String> domLines = readNonBlankLines(domPath);
		String domStr = String.join("", domLines);
		List<String> elements = parseDomElements(domLines);

		if (paperMode) {
			// Derive the Table-1 schedule from |dom|; overrides any forbidPerShot/numShots/forbidTotal args.
			forbidSchedule = paperSchedule(elements.size());
			forbidPerShot = 0;
			numShots = forbidSchedule.length + 1;
		}

		// Total number of distinct domain elements ever forbidden across the whole run (default: all of
		// them). Capping below |dom| deliberately leaves the tail of the domain unconstrained — the last
		// constraint shot then forbids only the remainder up to this cap instead of a full forbidPerShot
		// chunk. All solvers in the suite must use the same cap so their per-shot answer-set counts agree.
		int forbidTotal = paperMode ? intSum(forbidSchedule)
				: args.length >= 8 ? Integer.parseInt(args[7]) : elements.size();
		int forbidCap = Math.min(forbidTotal, elements.size());
		String forbidOrderFile = args.length >= 9 ? args[8] : null;

		// The order in which elements are forbidden across shots. The dom file order (hence the
		// solver's atom-creation order and initial heuristic preference) is left untouched; only
		// the forbidding sequence is permuted. This isolates the effect of *which* elements a shot
		// forbids on the live session's carried VSIDS/learned-nogood state. Counts are invariant to
		// order (forbidding any element removes exactly its single-selection answer set), so the
		// per-shot answer-set columns — and the live-vs-batch soundness check — are unchanged.
		//
		// When a forbidOrderFile is given (one element per line), it supplies the exact forbidding
		// sequence verbatim; the keyword is then only a display label. The harness uses this so every
		// solver in the suite (Alpha, rebuilt clingo, multi-shot clingo) forbids the identical set even
		// under a permuted order — rather than each reimplementing the same shuffle. Standalone runs
		// without the file fall back to computing asc/desc/scatter here.
		List<String> forbidSequence;
		if (forbidOrderFile != null && Files.isRegularFile(Paths.get(forbidOrderFile))) {
			forbidSequence = new ArrayList<>();
			for (String line : Files.readAllLines(Paths.get(forbidOrderFile))) {
				String t = line.trim();
				if (!t.isEmpty()) {
					forbidSequence.add(t);
				}
			}
		} else {
			forbidSequence = new ArrayList<>(elements);
			if ("desc".equals(forbidOrder)) {
				Collections.reverse(forbidSequence);
			} else if ("scatter".equals(forbidOrder)) {
				Collections.shuffle(forbidSequence, new Random(42));
			} else if (!"asc".equals(forbidOrder)) {
				System.err.println("Unknown forbidOrder: " + forbidOrder + " (use asc|desc|scatter)");
				System.exit(2);
			}
		}
		// Never index past the supplied sequence (a hand-written order file may be shorter than |dom|).
		forbidCap = Math.min(forbidCap, forbidSequence.size());

		Alpha alpha = newAlpha();
		AlphaSession session = alpha.newSession();
		session.add(encoding);
		session.add(domStr);

		// Warm JIT
		alpha.solve(alpha.readProgramString("p(1). q(X) :- p(X).")).count();

		String forbidRateDesc;
		if (forbidSchedule != null) {
			StringBuilder sb = new StringBuilder("schedule=");
			for (int i = 0; i < forbidSchedule.length; i++) {
				sb.append(i > 0 ? "," : "").append(forbidSchedule[i]);
			}
			forbidRateDesc = (paperMode ? "paper " : "") + sb;
		} else {
			forbidRateDesc = "forbidPerShot=" + forbidPerShot;
		}
		System.out.printf("%nmode=%s  |dom|=%d  %s  forbidTotal=%d (leaves %d unconstrained)  maxAnswerSets=%d  forbidOrder=%s%n",
				mode, elements.size(), forbidRateDesc, forbidCap, elements.size() - forbidCap, maxAnswerSets, forbidOrder);
		System.out.printf("%-6s | %-9s | %-12s | %-12s | %-12s%n",
				"shot", "constr +", "constr total", "answer sets", "alpha (s)");
		System.out.printf("%-6s-+-%-9s-+-%-12s-+-%-12s-+-%-12s%n",
				"------", "---------", "------------", "------------", "------------");

		StringBuilder allConstraints = new StringBuilder();
		int cumulativeConstraints = 0;
		int nextElement = 0;
		double total = 0;
		for (int shot = 1; shot <= numShots; shot++) {
			// Shot 1 is the unconstrained base; shots 2.. each forbid this shot's quota of elements (a
			// constant forbidPerShot, or the schedule entry for this shot when a schedule was supplied).
			int quotaThisShot = shot == 1 ? 0
					: forbidSchedule != null ? (shot - 2 < forbidSchedule.length ? forbidSchedule[shot - 2] : 0)
					: forbidPerShot;
			int addedThisShot = 0;
			StringBuilder chunk = new StringBuilder();
			if (shot > 1) {
				for (int i = 0; i < quotaThisShot && nextElement < forbidCap; i++) {
					String e = forbidSequence.get(nextElement++);
					chunk.append(":- p(").append(e).append(',').append(e).append(',').append(e)
							.append(',').append(e).append(',').append(e).append(',').append(e).append(").\n");
					addedThisShot++;
				}
			}
			cumulativeConstraints += addedThisShot;
			allConstraints.append(chunk);

			if ("batch".equals(mode)) {
				session = alpha.newSession();
				session.add(encoding);
				session.add(domStr);
				session.add(allConstraints.toString());
			} else if (addedThisShot > 0) {
				session.add(chunk.toString());
			}

			long t0 = System.nanoTime();
			long answerSets = session.solve().limit(maxAnswerSets).count();
			long t1 = System.nanoTime();
			double elapsed = (t1 - t0) / 1e9;
			total += elapsed;

			System.out.printf("%-6d | %-9d | %-12d | %-12d | %12.3f%s%n",
					shot, addedThisShot, cumulativeConstraints, answerSets, elapsed,
					answerSets == 0 ? "  (UNSAT)" : "");
		}

		System.out.printf("%n  total alpha time (%s): %.3fs over %d shots%n", mode, total, numShots);
	}

	/**
	 * The paper Table-1 forbid schedule for a domain of {@code domSize} constants: {@code X/Y} means the
	 * first constraint shot forbids {@code Y}, the next {@code Y/2}, and each subsequent shot half the
	 * previous bound. The paper's exact rows are returned verbatim (3/4 for |dom| 8–20, 5/128 for 500,
	 * 5/512 for 1000); other sizes fall back to a general front-loaded halving that always leaves ≥1
	 * element unconstrained (matching {@code examples/groundexp/bench-constraints-sweep.sh}).
	 */
	private static int[] paperSchedule(int domSize) {
		switch (domSize) {
			case 8: case 10: case 12: case 14: case 16: case 18: case 20:
				return new int[] {4, 2, 1};                 // 3/4
			case 500:
				return new int[] {256, 128, 64, 32, 16};    // 5/256 (Y = nearest pow2 to |dom|/2, leaves 4)
			case 1000:
				return new int[] {512, 256, 128, 64, 32};   // 5/512 (leaves 8)
			default:
				return halvingScheduleFallback(domSize);
		}
	}

	/** General front-loaded halving schedule (≤5 shots) for non-paper sizes; never forbids the last element. */
	private static int[] halvingScheduleFallback(int n) {
		int f = 1;
		while (f * 2 <= Math.max(1, n / 2)) {
			f *= 2;
		}
		List<Integer> s = new ArrayList<>();
		int cum = 0;
		for (int i = 0; i < 5 && f >= 1; i++) {
			int take = Math.min(f, (n - 1) - cum);
			if (take <= 0) {
				break;
			}
			s.add(take);
			cum += take;
			f /= 2;
		}
		if (s.isEmpty()) {
			s.add(Math.max(1, n - 1));
		}
		int[] out = new int[s.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = s.get(i);
		}
		return out;
	}

	private static int intSum(int[] a) {
		int s = 0;
		for (int v : a) {
			s += v;
		}
		return s;
	}

	private static List<String> readNonBlankLines(Path p) throws IOException {
		List<String> out = new ArrayList<>();
		for (String line : Files.readAllLines(p)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("%")) {
				out.add(line + "\n");
			}
		}
		return out;
	}

	/** Extract the single argument term of each {@code dom(<term>).} fact, in file order. */
	private static List<String> parseDomElements(List<String> domLines) {
		List<String> elements = new ArrayList<>();
		for (String line : domLines) {
			String s = line.trim();
			int open = s.indexOf('(');
			int close = s.lastIndexOf(')');
			if (open >= 0 && close > open) {
				elements.add(s.substring(open + 1, close).trim());
			}
		}
		return elements;
	}

	private static Alpha newAlpha() {
		return new AlphaImpl();
	}

	private IncrementalGroundExplosionConstraintBenchmark() {}
}
