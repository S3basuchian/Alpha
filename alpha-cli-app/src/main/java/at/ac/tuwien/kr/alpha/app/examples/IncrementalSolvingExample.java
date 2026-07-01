package at.ac.tuwien.kr.alpha.app.examples;

import java.util.Set;
import java.util.stream.Collectors;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;

/**
 * Runnable demonstrations of basic incremental solving via {@link AlphaSession}.
 *
 * Each example prints a header, the program fragments it adds, and the resulting answer sets.
 * To run from Gradle:
 *
 *   ./gradlew :alpha-cli-app:runIncrementalExample
 *
 * To run from an IDE, just invoke {@link #main(String[])}.
 */
public final class IncrementalSolvingExample {

	public static void main(String[] args) {
		quickStart();
		incrementalReachability();
		growingChoiceSpace();
		inspectAndReset();
		unsatViaAddedConstraint();
		backpropagationAcrossTimesteps();
	}

	/** Build up a small reachability problem in stages, then solve once. */
	private static void quickStart() {
		header("quickStart");
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();

		session.add("node(1). node(2). node(3).");
		session.add("edge(1,2). edge(2,3).");
		session.add(
				"reachable(X) :- start(X)."
				+ "reachable(Y) :- reachable(X), edge(X, Y).");
		session.add("start(1).");

		printAnswerSets(session);
	}

	/** Grow a graph one edge at a time and watch the reachable set expand. */
	private static void incrementalReachability() {
		header("incrementalReachability");
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();

		session.add(
				"reachable(X) :- start(X)."
				+ "reachable(Y) :- reachable(X), edge(X, Y).");
		session.add("start(1).");

		System.out.println("-- shot 1: start(1) only");
		printAnswerSets(session);

		session.add("edge(1,2).");
		System.out.println("-- shot 2: +edge(1,2)");
		printAnswerSets(session);

		session.add("edge(2,3). edge(3,4).");
		System.out.println("-- shot 3: +edge(2,3) +edge(3,4)");
		printAnswerSets(session);
	}

	/** Each new coin doubles the number of answer sets. */
	private static void growingChoiceSpace() {
		header("growingChoiceSpace");
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ heads(C) } :- coin(C).");

		for (int i = 1; i <= 4; i++) {
			session.add("coin(" + i + ").");
			long count = session.solve().count();
			System.out.printf("  %d coin(s) -> %d answer set(s)%n", i, count);
		}
	}

	/** getProgram returns an independent snapshot; reset starts over. */
	private static void inspectAndReset() {
		header("inspectAndReset");
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");

		System.out.println("Accumulated program:");
		System.out.println(indent(session.getProgram().toString()));
		printAnswerSets(session);

		session.reset();
		System.out.println("After reset:");
		printAnswerSets(session);
	}

	/** Adding a constraint between shots can prune or eliminate answer sets. */
	private static void unsatViaAddedConstraint() {
		header("unsatViaAddedConstraint");
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }.");
		System.out.println("After '{ a }.':");
		printAnswerSets(session);

		session.add(":- a.");
		System.out.println("After ':- a.':");
		printAnswerSets(session);

		session.add(":- not a.");
		System.out.println("After ':- not a.':");
		printAnswerSets(session);
	}

	/**
	 * Constraint added at a later timestep retroactively forces a truth value at an earlier one —
	 * the property the proposal calls <i>backpropagation</i>.
	 */
	private static void backpropagationAcrossTimesteps() {
		header("backpropagationAcrossTimesteps");
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();

		session.add("{ light(T) } :- time(T).");
		session.add("time(1).");

		System.out.println("-- shot 1: time(1) only");
		printAnswerSets(session);

		session.add("time(2).");
		session.add(":- time(T), not light(T).");
		System.out.println("-- shot 2: +time(2), +':- time(T), not light(T).'");
		printAnswerSets(session);
	}

	// --- helpers --------------------------------------------------------

	private static void header(String name) {
		System.out.println();
		System.out.println("======== " + name + " ========");
	}

	private static void printAnswerSets(AlphaSession session) {
		Set<AnswerSet> answerSets = session.solve().collect(Collectors.toCollection(java.util.LinkedHashSet::new));
		if (answerSets.isEmpty()) {
			System.out.println("  (no answer sets — UNSAT)");
			return;
		}
		for (AnswerSet as : answerSets) {
			System.out.println("  " + as);
		}
	}

	private static String indent(String s) {
		StringBuilder sb = new StringBuilder();
		for (String line : s.split("\n")) {
			sb.append("  ").append(line).append('\n');
		}
		return sb.toString();
	}

	private IncrementalSolvingExample() {
		// utility class
	}
}
