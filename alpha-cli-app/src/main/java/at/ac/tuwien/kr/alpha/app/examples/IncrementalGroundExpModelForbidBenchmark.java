package at.ac.tuwien.kr.alpha.app.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;
import at.ac.tuwien.kr.alpha.api.programs.Predicate;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;

/**
 * Ground-explosion constraint-streaming benchmark with a <em>model-dependent</em> forbid protocol:
 * each shot enumerates up to {@code maxAS} answer sets and forbids element(s) actually selected in
 * them — the realistic "solve, block solution(s) you just saw, re-solve" loop.
 *
 * <p><b>Own-sequence methodology.</b> Each solver mode drives its <em>own</em> forbid sequence from its
 * own enumerated answer sets (like the cutedge benchmark): there is no shared/replayed stream. Two modes:
 * <ul>
 *   <li>{@code mss} — one live {@link AlphaSession}; grounder/atom-store/learned-nogoods warm across shots.</li>
 *   <li>{@code rebuilt} — a fresh session each shot (encoding + dom + all constraints blocked so far).</li>
 * </ul>
 * Blocking granularity ({@code -Dge.forbidAll}): default blocks <b>one</b> random selected element per shot;
 * {@code -Dge.forbidAll=true} blocks <b>all</b> selected elements found this shot.
 *
 * <p>The empty-selection set (derives no {@code p}) is always present and never forbidden, so the program
 * never goes UNSAT. Only the enumerate-up-to-{@code maxAS} call is timed.
 *
 * Usage:
 *   IncrementalGroundExpModelForbidBenchmark &lt;encoding.lp&gt; &lt;dom.lp&gt; &lt;numShots&gt; &lt;maxAnswerSets&gt; [seed]
 * Modes via {@code -Dge.mode=mss|rebuilt|both} (default {@code both}, which also prints the speedup).
 */
public final class IncrementalGroundExpModelForbidBenchmark {

	private static boolean forbidAll;
	private static int maxAS;

	public static void main(String[] args) throws IOException {
		if (args.length < 4 || args.length > 5) {
			System.err.println("Usage: IncrementalGroundExpModelForbidBenchmark <encoding.lp> <dom.lp> <numShots> <maxAnswerSets> [seed]");
			System.exit(2);
		}
		Path encodingPath = Paths.get(args[0]);
		Path domPath = Paths.get(args[1]);
		int numShots = Integer.parseInt(args[2]);
		maxAS = Integer.parseInt(args[3]);
		long seed = args.length == 5 ? Long.parseLong(args[4]) : 42L;
		forbidAll = Boolean.getBoolean("ge.forbidAll");
		String mode = System.getProperty("ge.mode", "both");

		String encoding = Files.readString(encodingPath);
		String dom = Files.readString(domPath);

		at.ac.tuwien.kr.alpha.api.config.SystemConfig cfg = new at.ac.tuwien.kr.alpha.api.config.SystemConfig();
		String heuristic = System.getProperty("bench.heuristic");
		if (heuristic != null) {
			cfg.setBranchingHeuristicName(heuristic);
		}
		Alpha alpha = new AlphaImpl(cfg);
		alpha.solve(alpha.readProgramString("p(1). q(X) :- p(X).")).count(); // warm JIT

		System.out.printf("%nmodel-dependent forbid (%s, own sequence per mode)  maxAS=%d  shots=%d  seed=%d%n",
				forbidAll ? "block ALL found" : "block ONE random found", maxAS, numShots, seed);

		double mssTotal = -1;
		double rebuiltTotal = -1;
		if (mode.equals("mss") || mode.equals("both")) {
			mssTotal = runMss(alpha, encoding, dom, numShots, seed);
			System.out.printf("  total mss:     %.3fs over %d shots%n", mssTotal, numShots);
		}
		if (mode.equals("rebuilt") || mode.equals("both")) {
			rebuiltTotal = runRebuilt(alpha, encoding, dom, numShots, seed);
			System.out.printf("  total rebuilt: %.3fs over %d shots%n", rebuiltTotal, numShots);
		}
		if (mode.equals("both")) {
			System.out.printf("  speedup (rebuilt/mss): %.2fx  %s%n", rebuiltTotal / mssTotal,
					rebuiltTotal > mssTotal ? "(mss faster)" : "(mss SLOWER)");
		}
	}

	/** MSS mode: one live session; each shot solves, blocks its found selection(s), re-solves. */
	private static double runMss(Alpha alpha, String encoding, String dom, int numShots, long seed) {
		AlphaSession session = alpha.newSession();
		session.add(encoding);
		session.add(dom);
		Random rnd = new Random(seed);
		double total = 0;
		for (int shot = 1; shot <= numShots; shot++) {
			long t0 = System.nanoTime();
			List<AnswerSet> models = session.solve().limit(maxAS).collect(Collectors.toList());
			double shotSecs = (System.nanoTime() - t0) / 1e9;
			total += shotSecs;
			List<String> toForbid = chooseForbid(models, rnd);
			if (Boolean.getBoolean("ge.perShot")) {
				System.err.printf("    [mss] shot %-3d  %8.3fs  (cum %8.3fs)  models=%d  forbid=%s%n",
						shot, shotSecs, total, models.size(), toForbid);
				System.err.flush();
			}
			if (toForbid.isEmpty()) {
				break;
			}
			session.add(constraints(toForbid));
		}
		return total;
	}

	/** Rebuilt mode: fresh session each shot (encoding + dom + all constraints blocked so far); own sequence. */
	private static double runRebuilt(Alpha alpha, String encoding, String dom, int numShots, long seed) {
		Random rnd = new Random(seed);
		StringBuilder cons = new StringBuilder();
		double total = 0;
		for (int shot = 1; shot <= numShots; shot++) {
			AlphaSession session = alpha.newSession();
			session.add(encoding);
			session.add(dom);
			if (cons.length() > 0) {
				session.add(cons.toString());
			}
			long t0 = System.nanoTime();
			List<AnswerSet> models = session.solve().limit(maxAS).collect(Collectors.toList());
			total += (System.nanoTime() - t0) / 1e9;
			List<String> toForbid = chooseForbid(models, rnd);
			if (toForbid.isEmpty()) {
				break;
			}
			cons.append(constraints(toForbid));
		}
		return total;
	}

	/** The element(s) to forbid this shot: all selected (forbidAll) or one random selected element. */
	private static List<String> chooseForbid(List<AnswerSet> models, Random rnd) {
		Set<String> selected = new LinkedHashSet<>();
		for (AnswerSet as : models) {
			String e = selectedElement(as);
			if (e != null) {
				selected.add(e);
			}
		}
		if (selected.isEmpty()) {
			return new ArrayList<>();
		}
		if (forbidAll) {
			return new ArrayList<>(selected);
		}
		List<String> sel = new ArrayList<>(selected);
		return List.of(sel.get(rnd.nextInt(sel.size())));
	}

	/** The single {@code sel(i)} element of an answer set, or {@code null} for the empty-selection set. */
	private static String selectedElement(AnswerSet as) {
		for (Predicate p : as.getPredicates()) {
			if (p.getArity() == 1 && "sel".equals(p.getName())) {
				SortedSet<Atom> insts = as.getPredicateInstances(p);
				if (!insts.isEmpty()) {
					return insts.first().getTerms().get(0).toString();
				}
			}
		}
		return null;
	}

	/** Forbidding constraints {@code :- p(e,e,e,e,e,e).} for the given selected elements. */
	private static String constraints(List<String> elements) {
		StringBuilder sb = new StringBuilder();
		for (String e : elements) {
			sb.append(":- p(").append(e).append(',').append(e).append(',').append(e)
					.append(',').append(e).append(',').append(e).append(',').append(e).append(").\n");
		}
		return sb.toString();
	}

	private IncrementalGroundExpModelForbidBenchmark() {
	}
}
