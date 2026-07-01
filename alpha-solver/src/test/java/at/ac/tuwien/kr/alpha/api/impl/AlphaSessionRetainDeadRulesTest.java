package at.ac.tuwien.kr.alpha.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.config.SystemConfig;

/**
 * Soundness tests for the session's single retraction path: on a fact retraction that kills a ground
 * rule, the dead rule's beta atom is left in the AtomStore and its nogoods inert in the live solver (no
 * GC, no solver rebuild); each retraction shot drops the whole dl-0 assignment and re-derives the
 * fixpoint from the surviving fact units over the kept (inert) structural nogoods (full re-propagation).
 *
 * <p><b>Sound on retract AND re-add.</b> An earlier <em>surgical</em> un-assign left trail state that
 * didn't cleanly invert when a retracted fact returned (spurious UNSAT); switching the retraction reset
 * to full dl-0 re-propagation fixed it. These tests pin that the session agrees with batch on a single
 * dead-rule retraction, on multi-retraction, and on retract-then-re-add.
 */
public class AlphaSessionRetainDeadRulesTest {

	private static Alpha alpha() {
		return new AlphaImpl(new SystemConfig());
	}

	private static Set<AnswerSet> collect(AlphaSession session) {
		return session.solve().collect(Collectors.toSet());
	}

	private static Set<AnswerSet> batch(Alpha alpha, String program) {
		return alpha.solve(alpha.readProgramString(program)).collect(Collectors.toSet());
	}

	// ---- a single dead-rule retraction (no re-add) matches batch ----

	@Test
	public void deadRuleRetractionMatchesBatch() {
		Alpha alpha = alpha();
		AlphaSession session = alpha.newSession();
		session.add("p(X) :- q(X). q(1). q(2). q(3).");
		assertEquals(batch(alpha, "p(X) :- q(X). q(1). q(2). q(3)."), collect(session));

		session.removeFacts("q(2).");
		// p(2):-q(2) is now a dead ground rule (q(2) is fact-only, no other derivation).
		assertEquals(batch(alpha, "p(X) :- q(X). q(1). q(3)."), collect(session));
	}

	@Test
	public void deadChoiceRuleRetractionMatchesBatch() {
		String rules = "sel(X) :- dom(X), not nsel(X). nsel(X) :- dom(X), not sel(X). ";
		Alpha alpha = alpha();
		AlphaSession session = alpha.newSession();
		session.add(rules + "dom(1). dom(2). dom(3).");
		assertEquals(batch(alpha, rules + "dom(1). dom(2). dom(3)."), collect(session));

		session.removeFacts("dom(2).");
		assertEquals(batch(alpha, rules + "dom(1). dom(3)."), collect(session));
	}

	/** Multi-fact retraction in a single shot matches batch. */
	@Test
	public void multiRetractionMatchesBatch() {
		String rules = "sel(X) :- dom(X), not nsel(X). nsel(X) :- dom(X), not sel(X). win :- sel(1), sel(3). ";
		Alpha alpha = alpha();
		AlphaSession session = alpha.newSession();
		session.add(rules + "dom(1). dom(2). dom(3). dom(4).");
		collect(session);
		session.removeFacts("dom(2). dom(4).");
		assertEquals(batch(alpha, rules + "dom(1). dom(3)."), collect(session));
	}

	// ---- retract -> re-add is sound (full re-propagation) ----

	/**
	 * Retract-then-re-add matches batch. An earlier surgical un-assign made this yield spurious UNSAT;
	 * full dl-0 re-propagation on the retraction shot fixed it, so re-adding the retracted fact restores
	 * the derivations exactly.
	 */
	@Test
	public void retractThenReAddMatchesBatch() {
		String rules = "reach(X) :- start(X). reach(Y) :- reach(X), edge(X,Y). ";
		String full = rules + "start(1). edge(1,2). edge(2,3).";

		Alpha alpha = alpha();
		AlphaSession session = alpha.newSession();
		session.add(full);
		collect(session);
		session.removeFacts("edge(1,2).");
		collect(session);
		session.add("edge(1,2).");
		assertEquals(batch(alpha, full), collect(session), "re-adding the retracted fact restores the derivations");
	}
}
