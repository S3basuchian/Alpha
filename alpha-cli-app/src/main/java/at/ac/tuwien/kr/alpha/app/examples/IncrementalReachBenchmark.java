package at.ac.tuwien.kr.alpha.app.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;

/**
 * Incremental reach benchmark driver — streams edges into a live {@code AlphaSession} and re-solves
 * each shot (the pure-recursion, edge-addition counterpart to the choice-driven cutedge benchmark).
 *
 * Three optional modes (last arg):
 *   live   — consume one answer set per shot. Each subsequent solve reuses the live solver:
 *            grounder, atom store, NoGoodStore (incl. learned nogoods), and VSIDS scores all
 *            persist; the choice stack is cleared and any closing-assigned dl-0 atoms are
 *            un-assigned.
 *   rebuild — consume all answer sets per shot. Enumeration nogoods added during the previous
 *            shot to block already-seen answer sets are purged in-place between shots via
 *            {@code NoGoodStore.purgeEnumerationNoGoods()}; the solver itself is reused (same
 *            path as live, plus enumeration-purge bookkeeping). Pre-purge, this mode used to
 *            force a full solver rebuild on the next shot.
 *   batch  — destroy & rebuild the session each shot from scratch. Classic baseline.
 *
 * Usage:
 *   IncrementalReachBenchmark &lt;encoding.lp&gt; &lt;edges.lp&gt; &lt;numShots&gt; [mode]
 */
public final class IncrementalReachBenchmark {

	public static void main(String[] args) throws IOException {
		if (args.length != 3 && args.length != 4) {
			System.err.println("Usage: IncrementalReachBenchmark <encoding.lp> <edges.lp> <numShots> [live|rebuild|batch]");
			System.exit(2);
		}
		Path encodingPath = Paths.get(args[0]);
		Path edgesPath = Paths.get(args[1]);
		int numShots = Integer.parseInt(args[2]);
		String mode = args.length == 4 ? args[3] : "live";

		String encoding = Files.readString(encodingPath);
		List<String> edgeLines = readNonBlankLines(edgesPath);
		// -Dreach.oneEdgeShots: start from a (near-)full base graph and add edges per shot, instead of
		// streaming the whole graph in over numShots equal chunks. The base (all but the last
		// numShots*edgesPerShot edges) is solved in shot 1; each subsequent shot adds -Dreach.edgesPerShot
		// edges (default 1). This makes batch re-ground the WHOLE graph every shot (max cost) while live
		// pays only the per-shot delta — the reach analog of the coloring "grow" protocol. edgesPerShot lets
		// us vary the per-shot increment while holding the final graph and shot count fixed.
		boolean oneEdgeShots = Boolean.getBoolean("reach.oneEdgeShots");
		int edgesPerShot = Integer.getInteger("reach.edgesPerShot", 1);
		List<List<String>> chunks = oneEdgeShots
				? splitBasePlusChunks(edgeLines, numShots, edgesPerShot)
				: splitIntoChunks(edgeLines, numShots);

		Alpha alpha = newAlpha();
		AlphaSession session = alpha.newSession();
		session.add(encoding);

		// Warm up the JIT once with a tiny solve so the first shot doesn't pay the cold-cache cost.
		alpha.solve(alpha.readProgramString("p(1). q(X) :- p(X).")).count();

		System.out.printf("%nmode=%s%n", mode);
		System.out.printf("%-6s | %-10s | %-12s | %-12s | %-12s%n",
				"shot", "edges +", "edges total", "alpha (s)", "search (s)");
		System.out.printf("%-6s-+-%-10s-+-%-12s-+-%-12s-+-%-12s%n",
				"------", "----------", "------------", "------------", "------------");

		StringBuilder allEdges = new StringBuilder();
		int cumulative = 0;
		double total = 0;
		for (int shot = 1; shot <= chunks.size(); shot++) {
			List<String> chunk = chunks.get(shot - 1);
			cumulative += chunk.size();
			String chunkStr = String.join("", chunk);
			allEdges.append(chunkStr);

			if ("batch".equals(mode)) {
				session = alpha.newSession();
				session.add(encoding);
				session.add(allEdges.toString());
			} else {
				session.add(chunkStr);
			}

			long t0 = System.nanoTime();
			Iterator<AnswerSet> it = session.solve().iterator();
			long beforeFirst = System.nanoTime();
			boolean found = it.hasNext();
			if (found) {
				it.next(); // materialize one answer set
			}
			// In rebuild mode, drain the iterator so all answer sets are enumerated. The
			// next solve purges the enumeration nogoods in-place rather than rebuilding.
			if ("rebuild".equals(mode)) {
				while (it.hasNext()) { it.next(); }
			}
			long t1 = System.nanoTime();
			double prep = (beforeFirst - t0) / 1e9;
			double search = (t1 - beforeFirst) / 1e9;
			double elapsed = prep + search;
			total += elapsed;

			System.out.printf("%-6d | %-10d | %-12d | %12.3f | %12.3f%s%n",
					shot, chunk.size(), cumulative, elapsed, search, found ? "" : "  (UNSAT)");
		}

		System.out.printf("%n  total alpha time (%s): %.3fs over %d shots%n", mode, total, chunks.size());
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

	/**
	 * Build shots for the "full base + k edges per shot" protocol: shot 1 is the whole graph except its
	 * last {@code numShots * edgesPerShot} edges (the base), and each of the following {@code numShots}
	 * shots adds {@code edgesPerShot} held-out edges. Total shots = {@code numShots + 1}. If the graph has
	 * fewer than {@code numShots * edgesPerShot} edges the base is empty and the held-out edges are chunked
	 * into groups of {@code edgesPerShot} (the last chunk may be smaller). {@code edgesPerShot = 1}
	 * reproduces the original one-edge-per-shot protocol exactly.
	 */
	private static List<List<String>> splitBasePlusChunks(List<String> items, int numShots, int edgesPerShot) {
		int perShot = Math.max(1, edgesPerShot);
		int n = items.size();
		int baseCount = Math.max(0, n - numShots * perShot);
		List<List<String>> chunks = new ArrayList<>();
		chunks.add(new ArrayList<>(items.subList(0, baseCount))); // shot 1: the (near-)full base graph
		for (int i = baseCount; i < n; i += perShot) {
			chunks.add(new ArrayList<>(items.subList(i, Math.min(i + perShot, n)))); // k edges per subsequent shot
		}
		return chunks;
	}

	private static <T> List<List<T>> splitIntoChunks(List<T> items, int numChunks) {
		List<List<T>> chunks = new ArrayList<>();
		int n = items.size();
		int base = n / numChunks;
		int rem = n % numChunks;
		int offset = 0;
		for (int i = 0; i < numChunks; i++) {
			int size = base + (i < rem ? 1 : 0);
			chunks.add(new ArrayList<>(items.subList(offset, offset + size)));
			offset += size;
		}
		return chunks;
	}

	private static Alpha newAlpha() {
		return new AlphaImpl();
	}

	private IncrementalReachBenchmark() {}
}
