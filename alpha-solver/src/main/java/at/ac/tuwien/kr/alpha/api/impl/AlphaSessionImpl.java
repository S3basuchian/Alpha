package at.ac.tuwien.kr.alpha.api.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.AlphaSession;
import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.Solver;
import at.ac.tuwien.kr.alpha.api.config.GrounderHeuristicsConfiguration;
import at.ac.tuwien.kr.alpha.api.config.InputConfig;
import at.ac.tuwien.kr.alpha.api.config.SystemConfig;
import at.ac.tuwien.kr.alpha.api.programs.ASPCore2Program;
import at.ac.tuwien.kr.alpha.api.programs.NormalProgram;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;
import at.ac.tuwien.kr.alpha.commons.programs.Programs;
import at.ac.tuwien.kr.alpha.commons.programs.Programs.ASPCore2ProgramBuilder;
import at.ac.tuwien.kr.alpha.core.common.AtomStore;
import at.ac.tuwien.kr.alpha.core.common.AtomStoreImpl;
import at.ac.tuwien.kr.alpha.core.grounder.GrounderFactory;
import at.ac.tuwien.kr.alpha.core.grounder.NaiveGrounder;
import at.ac.tuwien.kr.alpha.core.programs.CompiledProgram;
import at.ac.tuwien.kr.alpha.core.programs.InternalProgram;
import at.ac.tuwien.kr.alpha.core.programs.rules.CompiledRule;
import at.ac.tuwien.kr.alpha.core.solver.DefaultSolver;
import at.ac.tuwien.kr.alpha.core.solver.SolverFactory;

/**
 * Default {@link AlphaSession} implementation backed by an {@link ASPCore2ProgramBuilder} and, after the first
 * {@link #solve} call, a long-lived {@link AtomStore} + {@link NaiveGrounder} pair wrapped in a
 * {@link SessionGrounder} that can replay cumulative grounder state to fresh solver instances when needed.
 *
 * <p>This class realizes <b>AlphaInc</b> (Algorithm 1 of "Boosting ASP by Incremental Lazy Grounding"): a
 * session state &sigma; = (P, N_s, N_l) — accumulated program, structural nogoods, learned nogoods — evolved
 * by the operation stream {@code add_F}/{@code add_C}/{@code add_R} ({@link #add}), {@code retract_F}
 * ({@link #removeFacts}) and {@code solve}. Each {@link #solve} runs one AlphaShot (Algorithm 2).
 *
 * Semantics:
 * <ul>
 *   <li>{@link #add} parses and accumulates ASP source.</li>
 *   <li>{@link #solve} returns answer sets of the currently-accumulated program.</li>
 *   <li><b>Live-solver path</b> (default after the first solve): the existing {@link DefaultSolver} is
 *       reused across shots. New rules and facts are pushed into the persistent grounder; the solver's
 *       trail is fully cleared (T &larr; &empty;), the previous shot's enumeration and foundedness-tainted
 *       nogoods are purged, VSIDS activity is reset, and the surviving units are re-forced. The sound
 *       LEARNT nogoods and all structural nogoods (with their watches) are kept — no re-ingest.</li>
 *   <li><b>Solver-only rebuild</b>: invoked by {@link #applyPendingRetractions} after a fact retraction,
 *       which nulls out {@code liveSolver}. The grounder and atom store survive; a fresh solver receives
 *       the cumulative nogoods via {@link SessionGrounder} replay.</li>
 *   <li><b>Full rebuild</b>: first solve, {@link #reset}, or a change of the answer-set filter.</li>
 * </ul>
 */
public final class AlphaSessionImpl implements AlphaSession {

	private final AlphaImpl alpha;
	private ASPCore2ProgramBuilder programBuilder;

	// Persistent state (initialized on first solve, kept across subsequent solves).
	private AtomStore atomStore;
	private NaiveGrounder grounder;
	private SessionGrounder sessionGrounder;
	private Solver liveSolver;          // null when no live solver available (first solve / after rebuild)
	private Predicate<at.ac.tuwien.kr.alpha.api.programs.Predicate> lastFilter;

	// Tracking since last full rebuild.
	private final List<Atom> pendingFacts = new ArrayList<>();
	private final List<ASPCore2Program> pendingRuleFragments = new ArrayList<>();

	// Fact retractions buffered since the last solve. Applied during {@link #applyPendingRetractions},
	// which drops the retracted facts' unit nogoods from the grounder and marks the shot so the live
	// solver retracts in place (clears the trail, drops learned nogoods, re-asserts surviving units).
	private final Set<Atom> pendingRetractions = new java.util.LinkedHashSet<>();

	// Set by applyPendingRetractions when a real retraction happened this shot, so the live-solver path
	// runs an in-place retraction (retractInPlace) instead of the monotone reset (resetForNewShot).
	private boolean retractionThisShot;

	AlphaSessionImpl(Alpha alpha) {
		this((AlphaImpl) alpha, null);
	}

	AlphaSessionImpl(Alpha alpha, ASPCore2Program initialProgram) {
		this((AlphaImpl) alpha, initialProgram);
	}

	private AlphaSessionImpl(AlphaImpl alpha, ASPCore2Program initialProgram) {
		this.alpha = alpha;
		this.programBuilder = Programs.builder();
		if (initialProgram != null) {
			this.programBuilder.accumulate(initialProgram);
		}
	}

	@Override
	public void add(String aspCode) {
		if (aspCode == null) {
			throw new NullPointerException("aspCode must not be null");
		}
		if (aspCode.isEmpty()) {
			return;
		}
		add(alpha.readProgramString(aspCode));
	}

	@Override
	public void add(ASPCore2Program program) {
		if (program == null) {
			throw new NullPointerException("program must not be null");
		}
		// Between-shot adds are restricted to facts and constraints. Adding a head-bearing rule whose
		// head predicate already has rules from a previous shot would weaken the existing head's support
		// and silently invalidate learned nogoods derived against it. Constraints (rules with no head)
		// only add restrictions and don't weaken anything — they are sound to add between shots.
		// Until learned-nogood provenance tracking lands, reject head-bearing rule extension; users
		// wanting to rewrite rules can call reset() first.
		if (atomStore != null) {
			for (at.ac.tuwien.kr.alpha.api.programs.rules.Rule<?> r : program.getRules()) {
				if (!r.isConstraint()) {
					throw new IllegalStateException(
							"AlphaSession.add only accepts facts and constraints after the first solve. "
							+ "Head-bearing rule extension between shots can silently invalidate learned "
							+ "nogoods derived against the previous head support. Call reset() to discard "
							+ "accumulated state and start fresh, then add the new rule in the initial "
							+ "program.");
				}
			}
		}
		programBuilder.accumulate(program);
		// If any of the added facts are currently scheduled for retraction, drop them from the
		// retraction set — the add wins (and the user just re-asserted them).
		if (!pendingRetractions.isEmpty()) {
			for (Atom fact : program.getFacts()) {
				pendingRetractions.remove(fact);
			}
		}
		if (atomStore == null) {
			// No persistent state yet; nothing to track.
			return;
		}
		if (program.getRules().isEmpty()) {
			pendingFacts.addAll(program.getFacts());
		} else {
			// Constraints-only fragment (head-bearing rules were blocked above). Route through the
			// rule-fragment path so the grounder's pendingFixedRules picks them up on next solve.
			pendingRuleFragments.add(program);
		}
	}

	@Override
	public void removeFacts(Atom fact) {
		if (fact == null) {
			throw new NullPointerException("fact must not be null");
		}
		recordRetraction(java.util.Collections.singletonList(fact));
	}

	@Override
	public void removeFacts(Iterable<Atom> facts) {
		if (facts == null) {
			throw new NullPointerException("facts must not be null");
		}
		recordRetraction(facts);
	}

	@Override
	public void removeFacts(String aspCode) {
		if (aspCode == null) {
			throw new NullPointerException("aspCode must not be null");
		}
		if (aspCode.isEmpty()) {
			return;
		}
		ASPCore2Program parsed = alpha.readProgramString(aspCode);
		if (!parsed.getRules().isEmpty()) {
			throw new IllegalArgumentException(
					"removeFacts only accepts fact-only fragments; the supplied code contains "
							+ parsed.getRules().size() + " rule(s).");
		}
		recordRetraction(parsed.getFacts());
	}

	@Override
	public void removeFacts(ASPCore2Program program) {
		if (program == null) {
			throw new NullPointerException("program must not be null");
		}
		recordRetraction(program.getFacts());
	}

	private void recordRetraction(Iterable<Atom> facts) {
		for (Atom fact : facts) {
			pendingRetractions.add(fact);
			// Also strip from any not-yet-applied pending fact deltas. Otherwise a fact added then
			// removed in the same session-between-solves window would still be pushed into the
			// persistent grounder before the rebuild path runs.
			pendingFacts.removeIf(f -> f.equals(fact));
		}
	}

	@Override
	public ASPCore2Program getProgram() {
		return Programs.builder().accumulate(programBuilder.build()).build();
	}

	@Override
	public Stream<AnswerSet> solve() {
		return solve(InputConfig.DEFAULT_FILTER);
	}

	@Override
	public Stream<AnswerSet> solve(Predicate<at.ac.tuwien.kr.alpha.api.programs.Predicate> filter) {
		if (!pendingRetractions.isEmpty()) {
			// Apply retractions grounder-side (filter the program builder, drop the retracted facts' unit
			// nogoods, mark the shot). The live solver is kept; the incremental path below retracts in place.
			applyPendingRetractions();
		}
		final boolean filterChanged = lastFilter != null && lastFilter != filter;
		// A previous shot that ended in a decision-level-0 conflict (UNSAT proven at the root) leaves the live
		// solver + grounder in a state the warm resets cannot soundly repair: there is no consistent dl-0
		// fixpoint to return to, and the lazy grounder may have short-circuited before grounding all reachable
		// instances. Reusing it yields spurious answer sets (re-solve/add) or missing derivations (retract), so
		// force a full rebuild instead. Search-exhausted UNSAT (a consistent dl-0 fixpoint existed) is handled
		// correctly by the warm resets and does not set this flag.
		final boolean priorShotEndedInDl0Conflict = liveSolver instanceof DefaultSolver
				&& ((DefaultSolver) liveSolver).hasEndedInDecisionLevelZeroConflict();
		final boolean grounderUsable = atomStore != null && !filterChanged && !priorShotEndedInDl0Conflict;

		Solver solver;
		if (!grounderUsable) {
			// Rebuild path — the only non-incremental path. Taken on the first solve, after {@link #reset},
			// and on a change of the answer-set filter. Fresh atom store + grounder + solver; the
			// SessionGrounder replays the cumulative structural state into the fresh solver.
			rebuildPersistentState(filter);
			sessionGrounder.armForReplay();
			solver = SolverFactory.getInstance(alpha.getSystemConfig(), atomStore, sessionGrounder);
			if (!(solver instanceof DefaultSolver)) {
				throw new IllegalStateException(
						"AlphaSession (incremental mode) requires the 'default' solver; configured solver is "
						+ solver.getClass().getSimpleName() + ".");
			}
			liveSolver = solver;
		} else {
			// Incremental path — every warm shot. Push new rules/facts, then either warm-start (monotone
			// add-only shot: keep trail + learned nogoods + VSIDS, reset to dl 0) or, if a fact was retracted
			// this shot, retract in place (clear the trail, drop learned nogoods, re-assert surviving units).
			// Either way the grounder, the nogood store, and VSIDS are kept — no re-ingest, no rebuild.
			List<CompiledRule> newRules = applyPendingRuleFragments();
			Set<at.ac.tuwien.kr.alpha.api.programs.Predicate> changedFactPredicates = applyPendingFacts();
			grounder.selectiveWakeUpForNewFacts(changedFactPredicates);
			if (!newRules.isEmpty()) {
				grounder.selectiveWakeUpForNewRules(newRules);
			}
			if (retractionThisShot) {
				// Retraction tears down the previous shot's enumeration nogoods AND drops all learned nogoods
				// from the store; forget both from the grounder's dedup registry so a later constraint that
				// grounds to a structurally-identical (now-dropped) nogood is re-emitted, not deduped away.
				sessionGrounder.forgetSolverInternalRegistrations(true);
				((DefaultSolver) liveSolver).retractInPlace(sessionGrounder.survivingUnitNoGoods());
				retractionThisShot = false;
			} else {
				// Monotone (add-only) shot: full trail clear T ← ∅, purge the previous shot's enumeration and
				// foundedness-tainted (TRANSIENT-tagged) nogoods, keep the sound LEARNT nogoods for cross-shot
				// reuse, re-force the surviving units, and reset VSIDS. This is the dual of the retraction case:
				// a fact added this shot can found a previously-unfounded atom, so nothing foundedness-derived may
				// survive — the unconditional enumeration purge guarantees that. Structural nogoods are kept (no
				// re-ground), so this stays far cheaper than a full rebuild. Forgetting only the enumeration
				// registry entries (false) matches the store's purge; the kept LEARNT entries stay registered.
				sessionGrounder.forgetSolverInternalRegistrations(false);
				((DefaultSolver) liveSolver).resetForNewShot(sessionGrounder.survivingUnitNoGoods());
			}
			solver = liveSolver;
		}
		// Cleared inside the live-solver branch; also clear here so a retraction that fell through to a
		// (re)build path does not leak the flag into the next shot.
		retractionThisShot = false;
		lastFilter = filter;

		Stream<AnswerSet> stream = StreamSupport.stream(solver.spliterator(), false);
		return alpha.getSystemConfig().isSortAnswerSets() ? stream.sorted() : stream;
	}

	/**
	 * Apply pending fact retractions.
	 *
	 * <ul>
	 *   <li><b>Session-mode</b> (grounder live): keep the grounder + atom store + cumulative structural
	 *       nogoods AND the live solver. Drop the retracted facts from the grounder's working memory
	 *       and from the cumulative replay's unit nogoods; record the retracted atom ids so the next
	 *       live-solver reset un-assigns them at dl 0 (with cascade through dependent derivations).
	 *       Structural nogoods stay valid because fact literals are not elided in session mode.</li>
	 *   <li><b>No persistent state yet</b>: nothing to do beyond filtering the programBuilder; the
	 *       first solve will build the (already-filtered) program from scratch.</li>
	 * </ul>
	 *
	 * In both cases, {@code programBuilder} is rebuilt so that {@link #getProgram()} and subsequent
	 * full rebuilds see the post-retraction program.
	 */
	private void applyPendingRetractions() {
		// Always update programBuilder so getProgram() and any future full rebuild see the right state.
		ASPCore2Program current = programBuilder.build();
		ASPCore2ProgramBuilder rebuilt = Programs.builder();
		for (Atom fact : current.getFacts()) {
			if (!pendingRetractions.contains(fact)) {
				rebuilt.addFact(fact);
			}
		}
		rebuilt.addRules(current.getRules());
		rebuilt.addInlineDirectives(current.getInlineDirectives());
		rebuilt.addTestCases(current.getTestCases());
		this.programBuilder = rebuilt;

		if (atomStore == null || grounder == null) {
			// No persistent state — next solve will full-build from the freshly-filtered programBuilder.
			this.pendingRetractions.clear();
			return;
		}

		// AlphaInc retract_F (Algorithm 1 case retract_F & Lemma 2 of "Boosting ASP by Incremental Lazy
		// Grounding"): remove each retracted fact's unit nogood {Ff}_1 from the structural store N_s and drop
		// ALL learned nogoods N_l — a learned resolvent may have been derived through {Ff}_1 and would be
		// unsound once f is gone (learning cannot be carried across a retraction).
		// Session-mode retraction: keep the grounder + atom store + all cumulative structural nogoods AND
		// the live solver. Drop only the retracted facts' unit nogoods from the grounder's cumulative
		// recording, and mark the shot so the live-solver path retracts in place: it clears the trail
		// (making every kept watch trivially valid — no re-ingest), drops all learned nogoods (any may be
		// unsound in the reduced program), and re-asserts the surviving units. VSIDS is preserved for free
		// (same solver object). Dead structural nogoods stay inert; re-adding a fact re-activates them.
		Set<Integer> droppedFactUnitNoGoodIds = grounder.retractFacts(pendingRetractions);
		this.pendingRetractions.clear();
		if (droppedFactUnitNoGoodIds.isEmpty()) {
			// Phantom retraction (every "removed" fact had never been asserted): nothing to invalidate.
			return;
		}
		this.sessionGrounder.gcRetractedState(droppedFactUnitNoGoodIds);
		this.retractionThisShot = true;
	}

	private Set<at.ac.tuwien.kr.alpha.api.programs.Predicate> applyPendingFacts() {
		Set<at.ac.tuwien.kr.alpha.api.programs.Predicate> changedFactPredicates = new HashSet<>();
		if (!pendingFacts.isEmpty()) {
			for (Atom fact : pendingFacts) {
				changedFactPredicates.add(fact.getPredicate());
			}
			grounder.extendWithFacts(pendingFacts);
			pendingFacts.clear();
		}
		return changedFactPredicates;
	}

	/**
	 * Compile each pending rule fragment (parse → normalise → InternalProgram) and push its rules
	 * + any embedded facts into the persistent grounder. Returns the list of newly-added compiled
	 * rules so the caller can wake up affected predicates.
	 */
	private List<CompiledRule> applyPendingRuleFragments() {
		List<CompiledRule> allNewRules = new ArrayList<>();
		if (pendingRuleFragments.isEmpty()) {
			return allNewRules;
		}
		for (ASPCore2Program fragment : pendingRuleFragments) {
			NormalProgram normalized = alpha.normalizeProgram(fragment);
			InternalProgram intern = (InternalProgram) alpha.performInternalProgramConstructionWithoutStratifiedEval(normalized);
			grounder.extendWithRules(intern.getRules());
			allNewRules.addAll(intern.getRules());
			if (!intern.getFacts().isEmpty()) {
				grounder.extendWithFacts(intern.getFacts());
			}
		}
		pendingRuleFragments.clear();
		return allNewRules;
	}

	@Override
	public void reset() {
		this.programBuilder = Programs.builder();
		this.atomStore = null;
		this.grounder = null;
		this.sessionGrounder = null;
		this.liveSolver = null;
		this.pendingFacts.clear();
		this.pendingRuleFragments.clear();
		this.pendingRetractions.clear();
		this.lastFilter = null;
	}

	private void rebuildPersistentState(Predicate<at.ac.tuwien.kr.alpha.api.programs.Predicate> filter) {
		ASPCore2Program program = programBuilder.build();
		NormalProgram normalized = alpha.normalizeProgram(program);
		// IMPORTANT: stratified evaluation prunes rules whose heads it pre-derives into facts. That
		// breaks incremental state retention because newly-added facts can no longer trigger those
		// rules (they're gone). For session-level use we keep the rules in the compiled program and
		// let the lazy grounder fire them on demand.
		CompiledProgram compiled = alpha.performInternalProgramConstructionWithoutStratifiedEval(normalized);

		this.atomStore = new AtomStoreImpl();
		SystemConfig cfg = alpha.getSystemConfig();
		GrounderHeuristicsConfiguration heur = GrounderHeuristicsConfiguration
				.getInstance(cfg.getGrounderToleranceConstraints(), cfg.getGrounderToleranceRules());
		heur.setAccumulatorEnabled(cfg.isGrounderAccumulatorEnabled());

		// Session mode: facts are materialized as real atoms in the AtomStore, with unit nogoods forcing
		// them TRUE, rather than elided from generated nogoods. This makes structural nogoods valid for
		// any subset of seen facts — the prerequisite for fact retraction without re-grounding.
		this.grounder = new NaiveGrounder(compiled, atomStore, filter, heur, cfg.isDebugInternalChecks(), true);
		this.sessionGrounder = new SessionGrounder(grounder);
		this.liveSolver = null;

		this.pendingFacts.clear();
		this.pendingRuleFragments.clear();
	}
}
