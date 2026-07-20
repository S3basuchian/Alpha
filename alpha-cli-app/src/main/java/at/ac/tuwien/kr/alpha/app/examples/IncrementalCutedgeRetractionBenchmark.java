package at.ac.tuwien.kr.alpha.app.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;
import at.ac.tuwien.kr.alpha.api.programs.Predicate;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;

/**
 * Cutedge "iterative edge-cutting" benchmark exercising the retraction path.
 *
 * The loop is the one a user would actually run:
 *   1. solve cutedge on the current graph -> the answer set marks exactly one edge as deleted
 *      (the {@code delete(a,b)} atom; the {@code edge(a,b)} fact itself is always in the program);
 *   2. *physically* retract that edge ({@code removeFacts("edge(a,b).")});
 *   3. re-solve -> the solver picks the next edge to cut; repeat for numShots shots.
 *
 * Unlike the replay variant, INCREMENTAL and BATCH each drive their OWN cut sequence: at every shot
 * a mode retracts the edge of its own first-found answer set. The two modes are therefore fully
 * independent trajectories -- if the warm incremental solver's first answer set differs from a cold
 * batch solve on the same graph, the sequences diverge. The final report prints both sequences and
 * whether they stayed identical, so divergence (if any) is visible rather than assumed away.
 *
 * Usage:
 *   IncrementalCutedgeRetractionBenchmark &lt;encoding.lp&gt; &lt;edges.lp&gt; &lt;numShots&gt; [warmups]
 *
 * warmups (default 3) is the number of discarded full-graph solves used to warm the JIT before the
 * timed incremental phase; a sweep over large instances passes a smaller value so the warm-up does
 * not dominate (or blow a wall-clock budget) on graphs where a single full solve already costs tens
 * of seconds.
 */
public final class IncrementalCutedgeRetractionBenchmark {

	public static void main(String[] args) throws IOException {
		if (args.length < 3 || args.length > 4) {
			System.err.println("Usage: IncrementalCutedgeRetractionBenchmark <encoding.lp> <edges.lp> <numShots> [warmups]");
			System.exit(2);
		}
		Path encodingPath = Paths.get(args[0]);
		Path edgesPath = Paths.get(args[1]);
		int numShots = Integer.parseInt(args[2]);
		int warmups = args.length == 4 ? Integer.parseInt(args[3]) : 3;

		String encoding = Files.readString(encodingPath);
		List<String> edgeLines = readEdgeFacts(edgesPath);

		Alpha alpha = newAlpha();
		// Warm JIT.
		alpha.solve(alpha.readProgramString("p(1). q(X) :- p(X).")).count();

		StringBuilder full = new StringBuilder();
		for (String e : edgeLines) {
			full.append(e);
		}

		// Warm the cutedge-specific grounding/solving paths with a few full-graph solves (discarded),
		// so the incremental phase (timed first) is not penalised by cold JIT vs batch (timed second).
		for (int w = 0; w < warmups; w++) {
			AlphaSession ws = alpha.newSession();
			ws.add(encoding);
			ws.add(full.toString());
			solveFirst(ws);
		}

		// ---- INCREMENTAL (session, live removeFacts, own cut sequence) -----------------------
		AlphaSession session = alpha.newSession();
		session.add(encoding);
		session.add(full.toString());

		System.out.printf("%n==== incremental (session + removeFacts, own sequence) ====%n");
		System.out.printf("%-6s | %-10s | %-14s | %-12s%n", "shot", "active", "cut edge", "alpha (s)");
		System.out.printf("%-6s-+-%-10s-+-%-14s-+-%-12s%n", "------", "----------", "--------------", "------------");

		List<String> incCutSeq = new ArrayList<>();
		int incActive = edgeLines.size();
		double incTotal = 0;

		for (int shot = 0; shot < numShots; shot++) {
			long t0 = System.nanoTime();
			AnswerSet as = solveFirst(session);
			double el = (System.nanoTime() - t0) / 1e9;
			incTotal += el;
			String edge = chosenEdge(as);
			System.out.printf("%-6d | %-10d | %-14s | %12.3f%n", shot, incActive, label(edge), el);
			if (edge == null) {
				break;
			}
			if (shot < numShots - 1) {
				session.removeFacts(edge);
				incCutSeq.add(edge);
				incActive--;
			}
		}
		System.out.printf("%n  total alpha time (incremental): %.3fs over %d shots%n", incTotal, incCutSeq.size() + 1);

		// ---- BATCH (rebuild each shot, own independent cut sequence) -------------------------
		System.out.printf("%n==== batch (rebuild each shot, own sequence) ====%n");
		System.out.printf("%-6s | %-10s | %-14s | %-12s%n", "shot", "active", "cut edge", "alpha (s)");
		System.out.printf("%-6s-+-%-10s-+-%-14s-+-%-12s%n", "------", "----------", "--------------", "------------");

		Set<String> activeEdges = new LinkedHashSet<>(edgeLines);
		List<String> batchCutSeq = new ArrayList<>();
		double batchTotal = 0;

		for (int shot = 0; shot < numShots; shot++) {
			AlphaSession bs = alpha.newSession();
			bs.add(encoding);
			StringBuilder cur = new StringBuilder();
			for (String e : activeEdges) {
				cur.append(e);
			}
			bs.add(cur.toString());
			dumpShot(shot + 1, encoding + cur);

			long t0 = System.nanoTime();
			AnswerSet as = solveFirst(bs);
			double el = (System.nanoTime() - t0) / 1e9;
			batchTotal += el;
			String edge = chosenEdge(as);
			System.out.printf("%-6d | %-10d | %-14s | %12.3f%n", shot, activeEdges.size(), label(edge), el);
			if (edge == null) {
				break;
			}
			if (shot < numShots - 1) {
				activeEdges.remove(edge);
				batchCutSeq.add(edge);
			}
		}
		System.out.printf("%n  total alpha time (batch): %.3fs over %d shots%n", batchTotal, batchCutSeq.size() + 1);

		System.out.printf("%n  SUMMARY  incremental=%.3fs  batch=%.3fs  speedup=%.2fx%n",
				incTotal, batchTotal, batchTotal / incTotal);
		System.out.printf("  incremental cut sequence: %s%n", labels(incCutSeq));
		System.out.printf("  batch       cut sequence: %s%n", labels(batchCutSeq));
		System.out.printf("  cut sequences identical: %b%n", incCutSeq.equals(batchCutSeq));
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

	/** Solve and materialize one answer set; returns it, or null if UNSAT. */
	private static AnswerSet solveFirst(AlphaSession session) {
		Iterator<AnswerSet> it = session.solve().iterator();
		return it.hasNext() ? it.next() : null;
	}

	/** The single {@code edge(a,b).} fact corresponding to the answer set's chosen {@code delete(a,b)}, or null. */
	private static String chosenEdge(AnswerSet as) {
		if (as == null) {
			return null;
		}
		for (Predicate p : as.getPredicates()) {
			if (p.getArity() == 2 && "delete".equals(p.getName())) {
				SortedSet<Atom> insts = as.getPredicateInstances(p);
				if (!insts.isEmpty()) {
					Atom d = insts.first();
					return "edge(" + d.getTerms().get(0) + "," + d.getTerms().get(1) + ").\n";
				}
			}
		}
		return null;
	}

	private static String label(String edgeFact) {
		return edgeFact == null ? "(none)" : edgeFact.trim();
	}

	private static List<String> labels(List<String> edgeFacts) {
		List<String> out = new ArrayList<>();
		for (String e : edgeFacts) {
			out.add(label(e));
		}
		return out;
	}

	private static List<String> readEdgeFacts(Path p) throws IOException {
		List<String> out = new ArrayList<>();
		for (String line : Files.readAllLines(p)) {
			String trimmed = line.trim();
			if (trimmed.startsWith("edge(")) {
				out.add(line + "\n");
			}
		}
		return out;
	}

	private static Alpha newAlpha() {
		at.ac.tuwien.kr.alpha.api.config.SystemConfig cfg = new at.ac.tuwien.kr.alpha.api.config.SystemConfig();
		String heuristic = System.getProperty("bench.heuristic");
		if (heuristic != null) {
			cfg.setBranchingHeuristicName(heuristic);
		}
		return new AlphaImpl(cfg);
	}

	private IncrementalCutedgeRetractionBenchmark() {}
}
