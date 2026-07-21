package at.ac.tuwien.kr.alpha.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.programs.ASPCore2Program;

/**
 * Tests for the basic incremental solving API exposed by {@link AlphaSession}.
 */
public class AlphaSessionImplTest {

	private static Set<AnswerSet> collect(AlphaSession session) {
		return session.solve().collect(Collectors.toSet());
	}

	@Test
	public void emptySessionYieldsSingleEmptyAnswerSet() {
		Alpha alpha = new AlphaImpl();
		try (AutoCloseableSession s = new AutoCloseableSession(alpha.newSession())) {
			Set<AnswerSet> actual = collect(s.session);
			Set<AnswerSet> expected = AnswerSetsParser.parse("{ }");
			assertEquals(expected, actual);
		}
	}

	/**
	 * UNSAT-shot closing residue must not leak into the next monotone shot. In shot 1, {@code e} is
	 * unsupported and closed FALSE, {@code d} loses support, and {@code :- not d} forces {@code d} and fires,
	 * so the shot is UNSAT with {@code e} closed FALSE at dl 0. Because an UNSAT shot produces no answer set,
	 * there is no dl-0 snapshot to restore from; {@code resetForNewShot} must instead strip the stale
	 * {@code F e} directly, otherwise adding {@code e} as a fact conflicts at dl 0 and spuriously stays UNSAT.
	 */
	@Test
	public void closedFalseAtomInUnsatShotThenAddedAsFact() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("d :- e. :- not d.");
		assertTrue(collect(session).isEmpty(), "e unsupported -> d unsupported -> ':- not d' fires -> UNSAT");
		session.add("e.");
		assertEquals(AnswerSetsParser.parse("{ d, e }"), collect(session),
				"e now a fact -> d derived -> SAT; must not carry stale closing FALSE for e");
	}

	@Test
	public void singleAddThenSolve() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");

		Set<AnswerSet> actual = collect(session);
		Set<AnswerSet> expected = AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2) }");
		assertEquals(expected, actual);
	}

	/**
	 * Regression: a fact added after the first solve may name an atom that is the head of an already-grounded
	 * uniquely-defined rule. Session mode must not retain the unique-head support nogood for such an atom,
	 * otherwise forcing the fact true would spuriously force the rule body true and drop answer sets. The
	 * mid-session result must match the same program added all at once.
	 */
	@Test
	public void factForUniqueRuleHeadAddedAfterSolveMatchesBatch() {
		Alpha alpha = new AlphaImpl();

		// Ground truth: { p } (a false) and { a, p } (a true); p is a fact, a is a free choice.
		AlphaSession batch = alpha.newSession();
		batch.add("{ a }. p :- a. p.");
		Set<AnswerSet> batchResult = collect(batch);
		assertEquals(2, batchResult.size());

		AlphaSession inc = alpha.newSession();
		inc.add("{ a }. p :- a.");
		collect(inc);
		inc.add("p.");
		assertEquals(batchResult, collect(inc));
	}

	@Test
	public void multipleAddsAccumulate() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		session.add("p(2).");
		session.add("q(X) :- p(X).");

		Set<AnswerSet> actual = collect(session);
		Set<AnswerSet> expected = AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2) }");
		assertEquals(expected, actual);
	}

	@Test
	public void solveAddSolveProducesDifferentAnswerSets() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). q(X) :- p(X).");

		Set<AnswerSet> firstShot = collect(session);
		assertEquals(AnswerSetsParser.parse("{ p(1), q(1) }"), firstShot);

		session.add("p(2).");

		Set<AnswerSet> secondShot = collect(session);
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2) }"), secondShot);
	}

	/**
	 * Simulates the canonical incremental use case: grow a graph one edge at a time and observe how the set of
	 * reachable nodes expands. This is the lazy-grounding analogue of clingo's classic incremental reachability
	 * example.
	 */
	@Test
	public void incrementalReachability() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add(
				"reachable(X) :- start(X)."
				+ "reachable(Y) :- reachable(X), edge(X, Y).");
		session.add("start(1).");

		assertEquals(AnswerSetsParser.parse("{ start(1), reachable(1) }"), collect(session));

		session.add("edge(1,2).");
		assertEquals(AnswerSetsParser.parse("{ start(1), edge(1,2), reachable(1), reachable(2) }"), collect(session));

		session.add("edge(2,3). edge(3,4).");
		assertEquals(
				AnswerSetsParser.parse("{ start(1), edge(1,2), edge(2,3), edge(3,4),"
						+ " reachable(1), reachable(2), reachable(3), reachable(4) }"),
				collect(session));
	}

	@Test
	public void incrementalChoiceProgramYieldsMoreAnswerSets() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		// One coin gives two answer sets; two coins give four.
		session.add("{ heads(C) } :- coin(C).");
		session.add("coin(1).");

		assertEquals(2, collect(session).size());

		session.add("coin(2).");
		assertEquals(4, collect(session).size());

		session.add("coin(3).");
		assertEquals(8, collect(session).size());
	}

	/**
	 * A constraint-rich choice program (2-colouring of a path): enumerating all answer sets forces
	 * conflict-driven learning <em>after</em> the first answer set, during which a learned nogood may
	 * resolve through an enumeration (answer-set-blocking) nogood. Such a nogood is sound only for the
	 * answer-set-blocked program and must not survive into a later shot, or it would unsoundly block a
	 * valid answer set of the extended program. Regresses the enumeration-derived learned-nogood gap.
	 */
	private static final String COLOURING =
			"col(N,1) :- node(N), not col(N,2)."
			+ "col(N,2) :- node(N), not col(N,1)."
			+ "node(1). node(2). node(3)."
			+ "edge(1,2). edge(2,3)."
			+ ":- edge(X,Y), col(X,C), col(Y,C).";

	@Test
	public void enumerationLearnedNoGoodsDoNotLeakIntoLaterShots() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add(COLOURING);
		Set<AnswerSet> firstShot = collect(session);     // enumerate all -> enumeration nogoods + learning
		assertTrue(firstShot.size() >= 2, "program should have several answer sets so enumeration runs");

		session.add("extra(1).");                        // monotone fact; learned nogoods are retained
		Set<AnswerSet> secondShot = collect(session);

		// Oracle: a cold-start session on the same accumulated program.
		AlphaSession cold = alpha.newSession();
		cold.add(COLOURING + "extra(1).");
		assertEquals(collect(cold), secondShot);
	}

	@Test
	public void consecutiveSolvesReproduceAllAnswerSets() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add(COLOURING);

		Set<AnswerSet> first = collect(session);
		Set<AnswerSet> second = collect(session);        // unchanged program; must reproduce every answer set
		assertTrue(first.size() >= 2, "program should have several answer sets so enumeration runs");
		assertEquals(first, second);
	}

	@Test
	public void resetClearsAccumulatedProgram() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2) }"), collect(session));

		session.reset();
		assertEquals(AnswerSetsParser.parse("{ }"), collect(session));

		session.add("q(7).");
		assertEquals(AnswerSetsParser.parse("{ q(7) }"), collect(session));
	}

	@Test
	public void getProgramReturnsIndependentSnapshot() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");

		ASPCore2Program snapshot = session.getProgram();
		int factsBefore = snapshot.getFacts().size();

		session.add("p(2). p(3).");

		// The previously-taken snapshot must not have grown.
		assertEquals(factsBefore, snapshot.getFacts().size());
		// And the session itself reflects the new state.
		assertEquals(3, session.getProgram().getFacts().size());
	}

	@Test
	public void getProgramAndDirectSolveAgree() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X). { r(X) } :- p(X).");

		Set<AnswerSet> viaSession = collect(session);
		Set<AnswerSet> viaProgram = alpha.solve(session.getProgram()).collect(Collectors.toSet());

		assertEquals(viaProgram, viaSession);
	}

	@Test
	public void sessionResultEqualsBatchSolveOfAccumulatedProgram() {
		Alpha alpha = new AlphaImpl();

		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		session.add("p(2).");
		session.add("q(X) :- p(X). { r(X) } :- p(X).");
		Set<AnswerSet> viaSession = collect(session);

		String concatenated =
				"p(1). p(2). q(X) :- p(X). { r(X) } :- p(X).";
		Set<AnswerSet> viaBatch = alpha.solve(alpha.readProgramString(concatenated)).collect(Collectors.toSet());

		assertEquals(viaBatch, viaSession);
	}

	@Test
	public void newSessionWithInitialProgram() {
		Alpha alpha = new AlphaImpl();
		ASPCore2Program initial = alpha.readProgramString("p(1). p(2). q(X) :- p(X).");

		AlphaSession session = alpha.newSession(initial);
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2) }"), collect(session));

		session.add("p(3).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), p(3), q(1), q(2), q(3) }"), collect(session));
	}

	@Test
	public void newSessionsAreIndependent() {
		Alpha alpha = new AlphaImpl();
		AlphaSession a = alpha.newSession();
		AlphaSession b = alpha.newSession();

		a.add("p(1).");
		b.add("p(2).");

		assertNotSame(a, b);
		assertEquals(AnswerSetsParser.parse("{ p(1) }"), a.solve().collect(Collectors.toSet()));
		assertEquals(AnswerSetsParser.parse("{ p(2) }"), b.solve().collect(Collectors.toSet()));
	}

	@Test
	public void filterIsApplied() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");

		Set<AnswerSet> filtered = session.solve(pred -> pred.getName().equals("q")).collect(Collectors.toSet());
		Set<AnswerSet> expected = AnswerSetsParser.parse("{ q(1), q(2) }");
		assertEquals(expected, filtered);
	}

	@Test
	public void addingUnsatConstraintMakesSessionUnsat() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }.");
		assertEquals(2, collect(session).size());

		session.add(":- a.");
		Set<AnswerSet> remaining = collect(session);
		assertEquals(1, remaining.size());
		assertTrue(remaining.iterator().next().getPredicates().isEmpty());

		session.add(":- not a.");
		assertTrue(collect(session).isEmpty());
	}

	@Test
	public void addNullThrows() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		assertThrows(NullPointerException.class, () -> session.add((String) null));
		assertThrows(NullPointerException.class, () -> session.add((ASPCore2Program) null));
	}

	@Test
	public void addEmptyStringIsNoOp() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		session.add("");
		assertEquals(AnswerSetsParser.parse("{ p(1) }"), collect(session));
	}

	// --- between-shot rule-add restriction -------------------------------

	/**
	 * Adding a head-bearing rule after the first solve must throw. The restriction exists because
	 * rule extension between shots can silently invalidate learned nogoods derived against the
	 * previous head support (until provenance tracking lands).
	 */
	@Test
	public void addingHeadBearingRuleInLaterShotThrows() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). p(3).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), p(3) }"), collect(session));

		assertThrows(IllegalStateException.class, () -> session.add("q(X) :- p(X)."));
	}

	/**
	 * Constraints (rules with no head) are sound to add between shots — they only restrict, never
	 * weaken support. They must be accepted.
	 */
	@Test
	public void addingConstraintInLaterShotWorks() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). p(3).");
		collect(session);

		session.add(":- p(2).");
		assertTrue(collect(session).isEmpty(), "UNSAT — p(2) is a fact, constraint forbids");
	}

	/**
	 * Choice rules ({a} style) extend support and are head-bearing, so they are also blocked.
	 */
	@Test
	public void addingChoiceRuleInLaterShotThrows() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		collect(session);

		assertThrows(IllegalStateException.class, () -> session.add("{ q } :- p(1)."));
	}

	/**
	 * The restriction lifts after {@link AlphaSession#reset()}: a brand-new program can be loaded.
	 */
	@Test
	public void resetAllowsNewRulesAgain() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). q(X) :- p(X).");
		collect(session);

		assertThrows(IllegalStateException.class, () -> session.add("r(X) :- q(X)."));

		session.reset();
		session.add("p(1). q(X) :- p(X). r(X) :- q(X).");
		assertEquals(AnswerSetsParser.parse("{ p(1), q(1), r(1) }"), collect(session));
	}

	/**
	 * Adding a ground constraint between shots must prune the previously-found answer sets.
	 */
	@Test
	public void groundConstraintAddedLaterPrunesAnswerSets() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }. { b }.");
		assertEquals(4, collect(session).size()); // {}, {a}, {b}, {a,b}

		session.add(":- a.");
		Set<AnswerSet> after = collect(session);
		assertEquals(2, after.size(),
				"Adding ':- a.' should prune answer sets containing a — expecting {} and {b}");
	}

	/**
	 * Chain of rule additions across multiple shots. Each shot adds another rule that
	 * depends on a predicate defined in the previous shot.
	 */
	@Test
	public void chainedRuleAdditionsAcrossShots() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		session.add("q(X) :- p(X).");
		session.add("r(X) :- q(X).");
		session.add("s(X) :- r(X).");
		assertEquals(
				AnswerSetsParser.parse("{ p(1), q(1), r(1), s(1) }"),
				collect(session));
	}

	// --- transitive chaining (the user's concern) -----------------------

	/**
	 * Two-step chain: a new fact triggers rule 1 producing a derived atom; that derived atom must in
	 * turn trigger rule 2. Tests that newly-derived atoms (NEW to working memory) properly trigger
	 * dependent rules via {@code updateAssignment}.
	 */
	@Test
	public void newFactTriggersRuleChain() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("q(X) :- p(X). r(X) :- q(X). p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(1), q(1), r(1) }"), collect(session));

		session.add("p(2).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2), r(1), r(2) }"), collect(session));
	}

	/**
	 * Three-step chain. Adding p(2) should cascade q(2), r(2), s(2) even though only p was added.
	 */
	@Test
	public void newFactTriggersThreeStepChain() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("q(X) :- p(X). r(X) :- q(X). s(X) :- r(X). p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(1), q(1), r(1), s(1) }"), collect(session));

		session.add("p(2).");
		assertEquals(
				AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2), r(1), r(2), s(1), s(2) }"),
				collect(session));
	}

	/**
	 * Join with a previously-derived atom. Adding a new fact must combine with previously-derived
	 * atoms to produce new ground rule instantiations — this is what selective wake-up enables.
	 *
	 * Setup: rule {@code keep(X,Y) :- edge(X,Y), delete(X1,Y1), X1 != X.} requires both edge and
	 * delete in working memory. Shot 1 sets up an edge and forces a delete via a choice rule and a
	 * constraint. Shot 2 adds a new edge — the new ground rule must combine the new edge with the
	 * existing delete.
	 */
	@Test
	public void newFactJoinsWithPreviouslyDerivedAtom() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		// One-edge graph; force delete(1,2) via a choice + constraint.
		session.add(
				"edge(1,2)."
				+ "{ delete(X,Y) } :- edge(X,Y)."
				+ ":- edge(X,Y), not delete(X,Y), not keep(X,Y)."
				+ "keep(X,Y) :- edge(X,Y), delete(X1,Y1), X1 != X.");
		Set<AnswerSet> shot1 = collect(session);
		// In the only answer set, delete(1,2) is chosen (no other edge to delete, no other choice
		// can satisfy the constraint).
		assertTrue(shot1.stream().anyMatch(a -> a.toString().contains("delete(1, 2)")));

		// Add a new edge. New ground rule keep(3,4) :- edge(3,4), delete(X1,Y1), X1 != 3 should
		// combine new edge(3,4) with previously-derived delete(1,2): yields keep(3,4).
		session.add("edge(3,4).");
		Set<AnswerSet> shot2 = collect(session);
		// Two answer sets possible (delete(1,2) or delete(3,4)). In both, the other edge gets kept.
		assertTrue(shot2.size() >= 1);
		// Verify the "delete(1,2) kept" branch produces keep(3,4):
		boolean producedKeep = shot2.stream().anyMatch(as ->
				as.toString().contains("delete(1, 2)") && as.toString().contains("keep(3, 4)"));
		assertTrue(producedKeep, "Expected the new edge(3,4) to be 'kept' when delete(1,2) is chosen — "
				+ "this requires combining the new edge fact with the previously-derived delete atom");
	}

	// --- state retention checks -----------------------------------------

	/**
	 * After the first solve, the session must use the state-retention path for fact-only additions
	 * (no full rebuild). We verify this by reflection on the package-private internals.
	 */
	@Test
	public void factOnlyAddDoesNotRebuildGrounder() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("p(1). q(X) :- p(X).");
		session.solve().count(); // first solve, builds grounder
		Object grounderBefore = readField(session, "grounder");

		session.add("p(2).");
		session.solve().count(); // second solve, should reuse grounder
		Object grounderAfter = readField(session, "grounder");

		assertSame(grounderBefore, grounderAfter,
				"Fact-only addition should reuse the persistent grounder (state retention)");
	}

	/**
	 * Constraints added between shots must extend the persistent grounder in place rather than
	 * forcing a full rebuild. The grounder reference is preserved across the addition.
	 */
	@Test
	public void constraintAddedBetweenShotsExtendsGrounderInPlace() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");
		session.solve().count();
		Object grounderBefore = readField(session, "grounder");

		session.add(":- q(2).");
		Set<AnswerSet> result = session.solve().collect(Collectors.toSet());
		Object grounderAfter = readField(session, "grounder");

		assertSame(grounderBefore, grounderAfter,
				"Adding a constraint should extend the persistent grounder in place, not rebuild it");
		assertTrue(result.isEmpty(), "p(2) is a fact, q(2) forced TRUE, constraint forbids");
	}

	/**
	 * Multiple consecutive solves without intervening adds also reuse the same grounder and
	 * produce the same answer sets.
	 */
	@Test
	public void noOpResolveReusesGrounderAndYieldsSameAnswers() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("{ a }. { b }.");
		Set<AnswerSet> first = session.solve().collect(Collectors.toSet());
		Object grounderBefore = readField(session, "grounder");
		Set<AnswerSet> second = session.solve().collect(Collectors.toSet());
		Object grounderAfter = readField(session, "grounder");

		assertSame(grounderBefore, grounderAfter, "No-add re-solves should reuse the grounder");
		assertEquals(first, second, "No-add re-solves should give the same answer sets");
	}

	private static Object readField(Object target, String fieldName) throws Exception {
		java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
		f.setAccessible(true);
		return f.get(target);
	}

	/**
	 * Live-solver path: when we only take ONE answer set per solve (so no enumeration nogoods are added),
	 * the underlying DefaultSolver instance must be reused across solves. Verifies the load-bearing
	 * claim of the live-solver optimization.
	 */
	@Test
	public void singleAnswerSetPerSolveReusesSolver() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("p(1). q(X) :- p(X).");
		// take only the first answer set — do NOT collect/exhaust the stream
		AnswerSet first = session.solve().findFirst().orElseThrow();
		assertTrue(first.toString().contains("q(1)"));
		Object solverBefore = readField(session, "liveSolver");

		session.add("p(2).");
		AnswerSet second = session.solve().findFirst().orElseThrow();
		assertTrue(second.toString().contains("q(2)"));
		Object solverAfter = readField(session, "liveSolver");

		assertSame(solverBefore, solverAfter,
				"Single-AS per solve should reuse the DefaultSolver across shots (live-solver path)");
	}

	/**
	 * Fully consuming the answer-set stream adds enumeration nogoods to the solver. On the next solve,
	 * {@link at.ac.tuwien.kr.alpha.core.solver.DefaultSolver#resetForNewShot} purges those nogoods
	 * in-place rather than rebuilding the solver, so the same {@code DefaultSolver} instance — along
	 * with its learned nogoods and branching-heuristic activity — is reused.
	 */
	@Test
	public void fullyConsumingStreamReusesSolverViaEnumerationPurge() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("{ a }. { b }.");
		// Fully consume — this enumerates all 4 answer sets, adding enumeration nogoods.
		session.solve().collect(Collectors.toSet());
		Object solverBefore = readField(session, "liveSolver");
		Object grounderBefore = readField(session, "grounder");

		session.solve().collect(Collectors.toSet());
		Object solverAfter = readField(session, "liveSolver");
		Object grounderAfter = readField(session, "grounder");

		assertSame(solverBefore, solverAfter,
				"Solver must be reused across full enumerations — enumeration nogoods are purged in-place");
		assertSame(grounderBefore, grounderAfter,
				"Grounder + atom store should also be reused");
	}

	/**
	 * After fully enumerating in shot 1, adding facts and fully enumerating in shot 2 must produce the
	 * answer sets of the extended program. The previous shot's enumeration nogoods must have been purged
	 * — otherwise they would block valid AS of the extended program.
	 */
	@Test
	public void fullEnumerationFollowedByFactAddProducesCorrectAnswerSets() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ heads(C) } :- coin(C).");
		session.add("coin(1). coin(2).");

		Set<AnswerSet> shot1 = collect(session);
		assertEquals(4, shot1.size(), "Two coins produce 4 answer sets");

		session.add("coin(3).");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(8, shot2.size(), "Three coins produce 8 answer sets — must include all combinations");
	}

	/**
	 * Adding a constraint between two full enumerations must restrict (not just additively block) the
	 * AS set. Verifies enumeration-nogood purging composes correctly with new structural nogoods.
	 */
	@Test
	public void fullEnumerationFollowedByConstraintRestrictsAnswerSets() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }. { b }.");

		Set<AnswerSet> shot1 = collect(session);
		assertEquals(4, shot1.size(), "Two free choices produce 4 answer sets");

		session.add(":- a, b.");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(3, shot2.size(), "After :- a, b. exactly the {a,b} answer set is forbidden");
	}

	/**
	 * A program with a single choice point produces a unary enumeration nogood after the first AS is
	 * blocked. Verifies that the unary case — the only one where an enumeration nogood propagates at
	 * decision level 0 — is correctly handled by the dl-0 un-assign path in
	 * {@link at.ac.tuwien.kr.alpha.core.solver.WritableAssignment#unassignAtDecisionLevelZero}.
	 */
	@Test
	public void singleChoicePointFullEnumerationFollowedByExtensionIsCorrect() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		// Initial program: one choice on a, plus a supported-by-a rule.
		session.add("{ a }. b :- a.");

		Set<AnswerSet> shot1 = collect(session);
		assertEquals(2, shot1.size(), "One free choice produces 2 answer sets");
		assertEquals(AnswerSetsParser.parse("{ a, b } { }"), shot1);

		// Adding fact c between shots — purge re-frees a (unary enum nogood), c forces in,
		// search re-finds both branches of a augmented with c.
		session.add("c.");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(2, shot2.size(), "Still two answer sets, both now containing c");
		assertEquals(AnswerSetsParser.parse("{ a, b, c } { c }"), shot2);
	}

	/**
	 * After two consecutive full enumerations on an extending program, the live solver must still be the
	 * same instance — proves both shots' enumeration nogoods were purged in-place, not by rebuild.
	 */
	@Test
	public void twoFullEnumerationsAcrossExtensionReuseSolver() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		// Three choice atoms gated by facts; we enable them one at a time.
		session.add("{ heads(C) } :- coin(C). coin(1). coin(2).");
		session.solve().collect(Collectors.toSet());
		Object solverShot1 = readField(session, "liveSolver");

		session.add("coin(3).");
		session.solve().collect(Collectors.toSet());
		Object solverShot2 = readField(session, "liveSolver");

		assertSame(solverShot1, solverShot2,
				"Live solver must survive both enumeration purges and the fact extension between them");
	}

	// --- fact retraction ------------------------------------------------

	/**
	 * Removing a previously-added fact must remove it from the next answer set.
	 */
	@Test
	public void removeFactDropsItFromAnswerSet() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2) }"), collect(session));

		session.removeFacts("p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(2) }"), collect(session));
	}

	/**
	 * Removing a fact that supported a derived atom must drop the derived atom too.
	 * This is the DLV2-style "edge removal" case at the simplest level.
	 */
	@Test
	public void removingSupportingFactDropsDerivedAtom() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), q(1), q(2) }"), collect(session));

		session.removeFacts("p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(2), q(2) }"), collect(session));
	}

	/**
	 * The classic tweety case under retraction: the kill-switch fact (penguin) is part of the initial
	 * program; removing it must restore the default conclusion (flies). This isolates the test to
	 * retraction; the dual direction (adding a kill-switch fact mid-session) is covered by
	 * {@link #addingNegationKillSwitchFactRetractsDefaultConclusion}.
	 */
	@Test
	public void retractingNegationKillSwitchRestoresDefaultConclusion() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("bird(tweety). penguin(tweety). flies(X) :- bird(X), not penguin(X).");
		assertEquals(AnswerSetsParser.parse("{ bird(tweety), penguin(tweety) }"), collect(session));

		session.removeFacts("penguin(tweety).");
		assertEquals(AnswerSetsParser.parse("{ bird(tweety), flies(tweety) }"), collect(session));
	}

	/**
	 * Soundness regression and the dual of {@link #retractingNegationKillSwitchRestoresDefaultConclusion}:
	 * adding a negation kill-switch fact mid-session. In shot 1 the atom {@code black(1)} is neither a fact
	 * nor defined by any rule, so classic grounding elides {@code not black(1)} as trivially-true and derives
	 * {@code foo} unconditionally. Adding {@code black(1)} as a fact in shot 2 must then retract {@code foo}.
	 * Session mode suppresses the elision (see {@code NoGoodGenerator#collectNegLiterals} no-defining-rule
	 * branch), so the structural body nogood references {@code black(1)}; the late fact's unit nogood forces
	 * it TRUE and falsifies the rule body. The mid-session result must match a from-scratch batch solve.
	 */
	@Test
	public void addingNegationKillSwitchFactRetractsDefaultConclusion() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("foo :- not black(1).");
		assertEquals(AnswerSetsParser.parse("{ foo }"), collect(session));

		session.add("black(1).");
		Set<AnswerSet> viaSession = collect(session);
		assertEquals(AnswerSetsParser.parse("{ black(1) }"), viaSession,
				"Adding fact black(1) must retract the default conclusion foo — the elided 'not black(1)' "
				+ "must not survive as trivially-true in session mode.");

		// Oracle: from-scratch solve of the accumulated program must agree.
		Set<AnswerSet> viaBatch = alpha.solve(alpha.readProgramString("foo :- not black(1). black(1)."))
				.collect(Collectors.toSet());
		assertEquals(viaBatch, viaSession);
	}

	// --- constraint path: elided-then-materialized literals -------------
	//
	// Constraints route through the same collectNegLiterals/collectPosLiterals as rules, so the
	// no-elision fix materializes underivable atoms inside CONSTRAINT nogoods too. Unlike a rule
	// body, a constraint's negative literal yields a {.., Fatom} nogood that *pressures the atom
	// toward TRUE*. Since such an atom is unsupported, soundness requires it to be pinned FALSE.
	// These tests probe every eventuality and cross-check against a from-scratch batch solve.

	private Set<AnswerSet> batch(Alpha alpha, String program) {
		return alpha.solve(alpha.readProgramString(program)).collect(Collectors.toSet());
	}

	/**
	 * Negative literal over an underivable predicate in a constraint of the INITIAL program.
	 * {@code :- a, not black(1).} forbids {@code a} (black(1) can never hold, so {@code not black(1)}
	 * always does). The materialized, unsupported {@code black(1)} must be pinned FALSE — otherwise the
	 * nogood {@code {Ta, Fblack(1)}} could be "satisfied" by spuriously forcing {@code black(1)} TRUE,
	 * admitting the unsound answer set {@code {a}}. Adding {@code black(1)} later makes the constraint
	 * vacuous and re-allows {@code a}.
	 */
	@Test
	public void constraintNegLiteralOverUnderivablePredicate_initialProgram() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }. :- a, not black(1).");
		Set<AnswerSet> shot1 = collect(session);
		assertEquals(batch(alpha, "{ a }. :- a, not black(1)."), shot1);
		assertEquals(AnswerSetsParser.parse("{ }"), shot1, "a must be forbidden; black(1) must not be spuriously true");

		session.add("black(1).");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(batch(alpha, "{ a }. :- a, not black(1). black(1)."), shot2);
		assertEquals(AnswerSetsParser.parse("{ black(1) } { a, black(1) }"), shot2);
	}

	/**
	 * Same constraint, but ADDED in a later shot (materialized mid-session via the constraint-add path),
	 * then relaxed by a later fact.
	 */
	@Test
	public void constraintNegLiteralOverUnderivablePredicate_addedLater() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }.");
		assertEquals(2, collect(session).size()); // {}, {a}

		session.add(":- a, not black(1).");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(AnswerSetsParser.parse("{ }"), shot2, "a forbidden after constraint; black(1) must stay false");

		session.add("black(1).");
		Set<AnswerSet> shot3 = collect(session);
		assertEquals(batch(alpha, "{ a }. :- a, not black(1). black(1)."), shot3);
	}

	/**
	 * Sharpest probe: a constraint whose ONLY body literal is a negation over an underivable predicate.
	 * {@code :- not black(1).} grounds to the unary nogood {@code {Fblack(1)}}, which unconditionally
	 * forces {@code black(1)} TRUE. With black(1) unsupported, shot 1 must be UNSAT (the constraint always
	 * fires); once black(1) is a fact, it becomes vacuous and the program is SAT.
	 */
	@Test
	public void constraintOnlyNegLiteralOverUnderivable_unsatThenSatAfterFact() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add(":- not black(1).");
		assertTrue(collect(session).isEmpty(),
				"UNSAT: black(1) cannot be derived, so 'not black(1)' holds and the constraint always fires");

		session.add("black(1).");
		assertEquals(AnswerSetsParser.parse("{ black(1) }"), collect(session),
				"after black(1) becomes a fact the constraint is vacuous -> SAT");
	}

	/**
	 * Positive literal over an underivable predicate in a constraint. {@code :- a, black(1).} is vacuous
	 * while black(1) is underivable (nogood {@code {Ta, Tblack(1)}} cannot fire), so it must NOT kill the
	 * constraint outright — once black(1) is a fact it must forbid {@code a}. The positive case is the
	 * benign direction (the nogood pressures the atom toward FALSE, matching its unsupported default).
	 */
	@Test
	public void constraintPosLiteralOverUnderivablePredicate() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }. :- a, black(1).");
		Set<AnswerSet> shot1 = collect(session);
		assertEquals(batch(alpha, "{ a }. :- a, black(1)."), shot1);
		assertEquals(2, shot1.size(), "black(1) underivable -> constraint vacuous -> {}, {a}");

		session.add("black(1).");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(batch(alpha, "{ a }. :- a, black(1). black(1)."), shot2);
		assertEquals(AnswerSetsParser.parse("{ black(1) }"), shot2, "now black(1) true -> forbids a -> only { black(1) }");
	}

	/**
	 * Full add/retract cycle through the no-defining-rule branch. black(1) starts underivable (materialized
	 * by the constraint), is added as a fact (relaxing the constraint), then retracted (re-activating it).
	 * The materialized atom must flip cleanly in both directions and agree with the batch oracle at each step.
	 */
	@Test
	public void constraintNegLiteralUnderivable_addThenRetractFactCycles() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ a }. :- a, not black(1).");
		assertEquals(AnswerSetsParser.parse("{ }"), collect(session), "shot 1: a forbidden");

		session.add("black(1).");
		assertEquals(batch(alpha, "{ a }. :- a, not black(1). black(1)."), collect(session));

		session.removeFacts("black(1).");
		Set<AnswerSet> shot3 = collect(session);
		assertEquals(batch(alpha, "{ a }. :- a, not black(1)."), shot3);
		assertEquals(AnswerSetsParser.parse("{ }"), shot3, "after retracting black(1) the constraint forbids a again");
	}

	/**
	 * Variadic constraint that grounds MULTIPLE underivable instances at once. {@code :- p(X), not black(X).}
	 * with {@code p} facts and {@code black} underivable forbids every {@code p(X)} — but the {@code p}'s are
	 * facts, so the program is UNSAT. Adding all the {@code black} facts makes every instance vacuous -> SAT.
	 * Exercises materialization of one atom per ground substitution.
	 */
	@Test
	public void constraintNegLiteralUnderivable_variadicMultipleInstances() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2). p(3). :- p(X), not black(X).");
		assertTrue(collect(session).isEmpty(), "every p(X) is a fact but forbidden by the constraint -> UNSAT");

		session.add("black(1). black(2). black(3).");
		Set<AnswerSet> shot2 = collect(session);
		assertEquals(batch(alpha, "p(1). p(2). p(3). black(1). black(2). black(3). :- p(X), not black(X)."), shot2);
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2), p(3), black(1), black(2), black(3) }"), shot2);
	}

	/**
	 * Last cell of the matrix: a head-bearing rule whose POSITIVE body literal is over an initially-
	 * underivable predicate. Under lazy grounding the rule is simply not instantiated until black(1) enters
	 * working memory, so foo is absent in shot 1 and appears once black(1) is a fact. (The collectPosLiterals
	 * no-defining-rule guard is defensive — lazy instantiation already yields the correct observable behaviour.)
	 */
	@Test
	public void rulePosLiteralOverUnderivablePredicate() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("foo :- black(1).");
		assertEquals(AnswerSetsParser.parse("{ }"), collect(session), "black(1) underivable -> foo not derived");

		session.add("black(1).");
		assertEquals(AnswerSetsParser.parse("{ black(1), foo }"), collect(session));
	}

	/**
	 * Graph 2-coloring with edge removal, mirroring the spirit of the DLV2 paper's 3-coloring example
	 * (which uses disjunctive heads not supported by Alpha's parser). A triangle has no 2-coloring;
	 * removing one edge makes the resulting path 2-colorable. The retraction must flip UNSAT → SAT.
	 */
	@Test
	public void edgeRemovalFlipsUnsatToSat() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add(
				"{ red(X) } :- node(X)."
				+ "diff(X,Y) :- edge(X,Y), red(X), not red(Y)."
				+ "diff(X,Y) :- edge(X,Y), not red(X), red(Y)."
				+ ":- edge(X,Y), not diff(X,Y).");

		// Shot 1: triangle — UNSAT (no 2-coloring of K3 exists).
		session.add("node(1). node(2). node(3).");
		session.add("edge(1,2). edge(2,3). edge(1,3).");
		Set<AnswerSet> triangleSAS = collect(session);
		assertTrue(triangleSAS.isEmpty(), "K3 must be unsatisfiable under 2-coloring");

		// Shot 2: remove edge(1,3) — now we have the path 1-2-3, which IS 2-colorable. SAT.
		session.removeFacts("edge(1,3).");
		Set<AnswerSet> pathSAS = collect(session);
		assertTrue(!pathSAS.isEmpty(), "Path graph must be satisfiable under 2-coloring after edge removal");
	}

	/**
	 * A session that adds, removes, and re-adds across several shots must agree with a from-scratch
	 * solve on the final program. This is the load-bearing equivalence test.
	 */
	@Test
	public void mixedAddRemoveSequenceMatchesFromScratchBaseline() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("reachable(X) :- start(X). reachable(Y) :- reachable(X), edge(X,Y).");
		session.add("start(1).");
		session.add("edge(1,2). edge(2,3). edge(3,4).");
		collect(session);

		session.removeFacts("edge(2,3).");
		collect(session);

		session.add("edge(2,4).");
		collect(session);

		session.removeFacts("edge(3,4).");
		Set<AnswerSet> viaSession = collect(session);

		// Equivalent from-scratch program at this point: edges {1->2, 2->4}, start=1; reachable = {1,2,4}.
		String batch = "reachable(X) :- start(X). reachable(Y) :- reachable(X), edge(X,Y)."
				+ "start(1). edge(1,2). edge(2,4).";
		Set<AnswerSet> viaBatch = alpha.solve(alpha.readProgramString(batch)).collect(Collectors.toSet());
		assertEquals(viaBatch, viaSession);
	}

	/**
	 * Soundness regression: a learned nogood derived in shot 1 (with `a` forced by its fact unit and
	 * `b` chosen) must not survive into shot 2 (where `a` has been retracted), otherwise it would
	 * exclude the valid answer set `{b}`.
	 *
	 * <p>Setup is the minimal program that exercises the learned-nogood-vs-retraction case:
	 * <pre>{@code
	 *   { b }.   a.   :- a, b.
	 * }</pre>
	 * Shot 1 has the unique answer set {@code {a}}. Conflict learning produces a unit learned nogood
	 * (effectively `b=F`) when search tries `b=TRUE` against `a=TRUE` + constraint. Without learned-
	 * nogood discard on retraction, that unit would still force `b=F` in shot 2 and the answer sets
	 * would be only `{{}}` instead of `{{}, {b}}`.
	 */
	@Test
	public void retractionDiscardsPotentiallyUnsoundLearnedNoGoods() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("{ b }. a. :- a, b.");

		Set<AnswerSet> shot1 = collect(session);
		assertEquals(AnswerSetsParser.parse("{ a }"), shot1);

		session.removeFacts("a.");
		Set<AnswerSet> shot2 = collect(session);
		Set<AnswerSet> expected = AnswerSetsParser.parse("{ } { b }");
		assertEquals(expected, shot2,
				"After retracting `a`, both {} and {b} must be valid answer sets — a stale learned "
				+ "nogood forcing b=F would wrongly exclude {b}.");
	}

	/**
	 * Removing a fact that was never added is a no-op and does not perturb the answer set.
	 */
	@Test
	public void removingUnknownFactIsNoop() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(1) }"), collect(session));

		session.removeFacts("p(42).");
		assertEquals(AnswerSetsParser.parse("{ p(1) }"), collect(session));
	}

	/**
	 * Add and remove the same fact between two solves: the second solve should not see it.
	 */
	@Test
	public void addThenRemoveBeforeSolveDropsTheFact() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		collect(session);

		session.add("p(2).");
		session.removeFacts("p(2).");
		assertEquals(AnswerSetsParser.parse("{ p(1) }"), collect(session));
	}

	/**
	 * Re-add a fact after removing it: it must appear in the subsequent answer set.
	 */
	@Test
	public void removeThenReAddRestoresTheFact() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1). p(2).");
		collect(session);

		session.removeFacts("p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(2) }"), collect(session));

		session.add("p(1).");
		assertEquals(AnswerSetsParser.parse("{ p(1), p(2) }"), collect(session));
	}

	/**
	 * Passing rules to {@code removeFacts(String)} is rejected — we only allow fact-only fragments.
	 */
	@Test
	public void removeFactsRejectsRules() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("p(1).");
		assertThrows(IllegalArgumentException.class, () -> session.removeFacts("q(X) :- p(X)."));
	}

	/**
	 * Null arguments to removeFacts variants throw.
	 */
	@Test
	public void removeFactsNullThrows() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		assertThrows(NullPointerException.class, () -> session.removeFacts((String) null));
		assertThrows(NullPointerException.class, () -> session.removeFacts((Iterable<at.ac.tuwien.kr.alpha.api.programs.atoms.Atom>) null));
		assertThrows(NullPointerException.class, () -> session.removeFacts((ASPCore2Program) null));
		assertThrows(NullPointerException.class, () -> session.removeFacts((at.ac.tuwien.kr.alpha.api.programs.atoms.Atom) null));
	}

	/**
	 * After a retraction shot, the next monotone add must again take the fast path (grounder reused
	 * across the post-retraction window). This protects the load-bearing performance claim.
	 */
	@Test
	public void monotoneAdditionAfterRetractionStillReusesGrounder() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");
		collect(session);

		session.removeFacts("p(1).");
		collect(session);                              // in-place retraction (grounder kept)
		Object grounderAfterRetraction = readField(session, "grounder");

		session.add("p(3).");                          // monotone add — should reuse
		collect(session);
		Object grounderAfterMonotone = readField(session, "grounder");

		assertSame(grounderAfterRetraction, grounderAfterMonotone,
				"Post-retraction monotone additions must take the state-retention fast path");
	}

	/**
	 * Retraction keeps the live solver: the in-place path clears the trail, drops learned nogoods, and
	 * re-asserts the surviving units on the same {@link at.ac.tuwien.kr.alpha.core.solver.DefaultSolver}
	 * instance (structural nogoods and VSIDS are preserved — no re-ingest, no rebuild).
	 */
	@Test
	public void retractionPreservesLiveSolverInPlace() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("p(1). p(2). q(X) :- p(X).");
		session.solve().findFirst().orElseThrow();
		Object solverBefore = readField(session, "liveSolver");

		session.removeFacts("p(1).");
		AnswerSet after = session.solve().findFirst().orElseThrow();
		Object solverAfter = readField(session, "liveSolver");

		assertSame(solverBefore, solverAfter,
				"Retraction must retract in place on the same live solver, not rebuild it");
		String s = after.toString();
		assertTrue(s.contains("p(2)") && s.contains("q(2)"), "surviving fact p(2) still derives q(2)");
		assertTrue(!s.contains("p(1)") && !s.contains("q(1)"), "retracted p(1) and its consequence q(1) are gone");
	}

	/**
	 * The defining property of the no-fact-elision path: retraction preserves the grounder and atom
	 * store. This is what amortizes grounding across retraction shots.
	 */
	@Test
	public void retractionPreservesGrounderAndAtomStore() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("p(1). p(2). p(3). q(X) :- p(X).");
		session.solve().findFirst().orElseThrow();
		Object grounderBefore = readField(session, "grounder");
		Object atomStoreBefore = readField(session, "atomStore");
		Object sessionGrounderBefore = readField(session, "sessionGrounder");

		session.removeFacts("p(1).");
		session.solve().findFirst().orElseThrow();
		Object grounderAfter = readField(session, "grounder");
		Object atomStoreAfter = readField(session, "atomStore");
		Object sessionGrounderAfter = readField(session, "sessionGrounder");

		assertSame(grounderBefore, grounderAfter,
				"Retraction in session mode must preserve the grounder (no re-grounding)");
		assertSame(atomStoreBefore, atomStoreAfter,
				"Retraction must preserve the atom store (atom ids stay stable)");
		assertSame(sessionGrounderBefore, sessionGrounderAfter,
				"Retraction must preserve the SessionGrounder (cumulative structural nogoods reused)");
	}

	/**
	 * Re-adding a previously-retracted fact must restore its dependent derivations <em>without</em>
	 * re-grounding. This is the load-bearing test for the suppression-with-restore mechanism: if we
	 * had dropped the structural nogoods (instead of suppressing them), the re-added fact would have
	 * to re-trigger grounding to produce them again. With suppression, the cumulative state retains
	 * them and a simple unsuppress restores them.
	 */
	@Test
	public void reAddingRetractedFactRestoresDerivations() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add("edge(1,2). edge(2,3). reach(X) :- start(X). reach(Y) :- reach(X), edge(X,Y). start(1).");
		assertEquals(AnswerSetsParser.parse("{ edge(1,2), edge(2,3), start(1), reach(1), reach(2), reach(3) }"),
				collect(session));

		session.removeFacts("edge(1,2).");
		assertEquals(AnswerSetsParser.parse("{ edge(2,3), start(1), reach(1) }"), collect(session));

		// Re-add edge(1,2) — should restore reach(2) and reach(3) via the previously-suppressed
		// structural nogoods (no re-grounding from scratch).
		session.add("edge(1,2).");
		assertEquals(AnswerSetsParser.parse("{ edge(1,2), edge(2,3), start(1), reach(1), reach(2), reach(3) }"),
				collect(session));
	}

	/**
	 * Cyclic retraction/re-addition exercises both directions of the suppression machinery.
	 */
	@Test
	public void cyclicRetractionAndReAdditionMatchesBatch() {
		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		String rules = "reach(X) :- start(X). reach(Y) :- reach(X), edge(X,Y).";
		session.add(rules);
		session.add("start(1). edge(1,2). edge(2,3). edge(3,4).");
		collect(session);

		// Cycle 1
		session.removeFacts("edge(2,3).");
		collect(session);
		session.add("edge(2,3).");
		collect(session);

		// Cycle 2
		session.removeFacts("edge(1,2). edge(3,4).");
		collect(session);
		session.add("edge(1,2). edge(3,4).");

		// Final state must equal a from-scratch batch solve of the equivalent program.
		Set<AnswerSet> viaSession = collect(session);
		String batch = rules + " start(1). edge(1,2). edge(2,3). edge(3,4).";
		Set<AnswerSet> viaBatch = alpha.solve(alpha.readProgramString(batch)).collect(Collectors.toSet());
		assertEquals(viaBatch, viaSession);
	}

	/**
	 * Multiple retraction shots in a row must each be served by the surgical-removal path, not by full
	 * rebuild. Tests that the path is stable across repeated retractions.
	 */
	@Test
	public void repeatedRetractionsPreserveGrounder() throws Exception {
		Alpha alpha = new AlphaImpl();
		AlphaSessionImpl session = (AlphaSessionImpl) alpha.newSession();
		session.add("edge(1,2). edge(2,3). edge(3,4). edge(4,5).");
		session.add("reach(X) :- start(X). reach(Y) :- reach(X), edge(X,Y). start(1).");
		session.solve().findFirst().orElseThrow();
		Object grounderInitial = readField(session, "grounder");

		session.removeFacts("edge(1,2).");
		session.solve().findFirst().orElseThrow();
		assertSame(grounderInitial, readField(session, "grounder"));

		session.removeFacts("edge(2,3).");
		session.solve().findFirst().orElseThrow();
		assertSame(grounderInitial, readField(session, "grounder"));

		session.removeFacts("edge(3,4).");
		session.solve().findFirst().orElseThrow();
		assertSame(grounderInitial, readField(session, "grounder"));
	}

	/**
	 * Repro for a soundness regression in reach-style retraction with tombstoning: after retracting
	 * an edge and adding new ones, the next solve sometimes returns UNSAT when there is in fact a
	 * valid answer set. Cross-checks against batch mode.
	 */
	@Test
	public void retractionAddSequencePreservesAnswerSets() throws Exception {
		String encoding = "reachable(X,Y) :- edge(X,Y). "
				+ "reachable(X,Y) :- reachable(X,Z), edge(Z,Y). "
				+ "source(1). target(5). "
				+ "target_reached :- source(S), target(T), reachable(S,T).";

		Alpha alpha = new AlphaImpl();
		AlphaSession session = alpha.newSession();
		session.add(encoding);
		session.add("edge(1,2). edge(2,3). edge(3,4). edge(4,5).");
		long count1 = session.solve().count();
		assertTrue(count1 > 0, "shot 1 must be SAT (chain 1->2->3->4->5 exists)");

		session.add("edge(5,6).");
		long count2 = session.solve().count();
		assertTrue(count2 > 0, "shot 2 must be SAT");

		session.removeFacts("edge(2,3).");
		long count3 = session.solve().count();
		assertTrue(count3 > 0, "shot 3 must be SAT (chain is broken but program still has an AS — answer just won't include target_reached)");

		session.add("edge(2,3).");
		long count4 = session.solve().count();
		assertTrue(count4 > 0, "shot 4 must be SAT");

		session.removeFacts("edge(3,4).");
		long count5 = session.solve().count();
		assertTrue(count5 > 0, "shot 5 must be SAT");
	}

	// --- helper ---------------------------------------------------------

	/**
	 * Wrapper to let tests use try-with-resources style even though the API itself does not currently mandate
	 * AutoCloseable. Keeps the door open for future resources without making tests verbose.
	 */
	private static final class AutoCloseableSession implements AutoCloseable {
		final AlphaSession session;
		AutoCloseableSession(AlphaSession session) {
			this.session = session;
		}

		@Override
		public void close() {
			session.reset();
		}
	}
}
