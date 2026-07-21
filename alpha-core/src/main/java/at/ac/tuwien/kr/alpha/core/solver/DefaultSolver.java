/**
 * Copyright (c) 2016-2019, the Alpha Team.
 * All rights reserved.
 *
 * Additional changes made by Siemens.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1) Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2) Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package at.ac.tuwien.kr.alpha.core.solver;

import static at.ac.tuwien.kr.alpha.commons.util.Util.oops;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomOf;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomToLiteral;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomToNegatedLiteral;
import static at.ac.tuwien.kr.alpha.core.solver.NoGoodStore.LBD_NO_VALUE;
import static at.ac.tuwien.kr.alpha.core.solver.heuristics.BranchingHeuristic.DEFAULT_CHOICE_LITERAL;
import static at.ac.tuwien.kr.alpha.core.solver.learning.GroundConflictNoGoodLearner.ConflictAnalysisResult.UNSAT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
// ThriceTruth is in this package (at.ac.tuwien.kr.alpha.core.solver); no import needed.
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.StatisticsReportingSolver;
import at.ac.tuwien.kr.alpha.api.config.SystemConfig;
import at.ac.tuwien.kr.alpha.api.grounder.Substitution;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;
import at.ac.tuwien.kr.alpha.api.programs.atoms.BasicAtom;
import at.ac.tuwien.kr.alpha.api.programs.atoms.ComparisonAtom;
import at.ac.tuwien.kr.alpha.api.programs.literals.Literal;
import at.ac.tuwien.kr.alpha.api.programs.terms.ConstantTerm;
import at.ac.tuwien.kr.alpha.core.common.AtomStore;
import at.ac.tuwien.kr.alpha.core.common.NoGood;
import at.ac.tuwien.kr.alpha.core.grounder.Grounder;
import at.ac.tuwien.kr.alpha.core.grounder.ProgramAnalyzingGrounder;
import at.ac.tuwien.kr.alpha.core.programs.atoms.RuleAtom;
import at.ac.tuwien.kr.alpha.core.programs.rules.CompiledRule;
import at.ac.tuwien.kr.alpha.core.solver.heuristics.BranchingHeuristic;
import at.ac.tuwien.kr.alpha.core.solver.heuristics.BranchingHeuristicFactory;
import at.ac.tuwien.kr.alpha.core.solver.heuristics.ChainedBranchingHeuristics;
import at.ac.tuwien.kr.alpha.core.solver.heuristics.HeuristicsConfiguration;
import at.ac.tuwien.kr.alpha.core.solver.heuristics.NaiveHeuristic;
import at.ac.tuwien.kr.alpha.core.solver.learning.GroundConflictNoGoodLearner;

/**
 * The new default solver employed in Alpha.
 *
 * Copyright (c) 2016-2021, the Alpha Team.
 */
public class DefaultSolver extends AbstractSolver implements StatisticsReportingSolver {
	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSolver.class);

	private final NoGoodStore store;
	private final ChoiceManager choiceManager;
	private final WritableAssignment assignment;
	private final GroundConflictNoGoodLearner learner;
	private final BranchingHeuristic branchingHeuristic;

	private int mbtAtFixpoint;
	private int conflictsAfterClosing;
	private final boolean disableJustifications;
	// Justification after closing is disabled; the case is not fully worked out yet.
	private final boolean disableJustificationAfterClosing = true;
	private final boolean disableNoGoodDeletion;

	private static class SearchState {
		boolean hasBeenInitialized;
		boolean isSearchSpaceCompletelyExplored;
		/**
		 * True if search reached fixpoint and all remaining unassigned atoms have been set to false.
		 */
		boolean afterAllAtomsAssigned;
	}
	private final SearchState searchState = new SearchState();

	/**
	 * Set to {@code true} when {@link #prepareForSubsequentAnswerSet()} added an enumeration nogood to the
	 * store. The between-shot reset ({@link #resetInPlace}) purges all transient nogoods unconditionally and
	 * clears this flag; it is kept for its diagnostic value.
	 */
	private boolean enumerationUsed = false;

	/**
	 * Set to {@code true} when this shot's search terminated in a conflict at decision level 0 — an UNSAT
	 * proven at the root (an empty nogood, or conflict analysis that resolved to dl 0). Such a shot leaves no
	 * consistent dl-0 fixpoint and may have short-circuited grounding, so the incremental resets
	 * ({@link #resetForNewShot(Collection)} / {@link #retractInPlace(Collection)}) cannot soundly repair it and the
	 * session must rebuild from scratch before the next shot. Contrast search-exhausted UNSAT (conflict only
	 * after choices, over a consistent dl-0 fixpoint), which the warm resets handle correctly. Reset at each
	 * shot start; read by the session via {@link #hasEndedInDecisionLevelZeroConflict()}.
	 */
	private boolean endedInDecisionLevelZeroConflict = false;

	/**
	 * @return whether the last shot ended in a decision-level-0 conflict (see
	 *         {@link #endedInDecisionLevelZeroConflict}). The session uses this to force a full rebuild
	 *         instead of a warm reset before the next shot.
	 */
	public boolean hasEndedInDecisionLevelZeroConflict() {
		return endedInDecisionLevelZeroConflict;
	}

	private final PerformanceLog performanceLog;
	
	public DefaultSolver(AtomStore atomStore, Grounder grounder, NoGoodStore store, WritableAssignment assignment, Random random, SystemConfig config, HeuristicsConfiguration heuristicsConfiguration) {
		super(atomStore, grounder);

		this.assignment = assignment;
		this.store = store;
		this.choiceManager = new ChoiceManager(assignment, store);
		this.choiceManager.setChecksEnabled(config.isDebugInternalChecks());
		this.learner = new GroundConflictNoGoodLearner(assignment, atomStore);
		this.branchingHeuristic = chainFallbackHeuristic(grounder, assignment, random, heuristicsConfiguration);
		this.disableJustifications = config.isDisableJustificationSearch();
		this.disableNoGoodDeletion = config.isDisableNoGoodDeletion();
		this.performanceLog = new PerformanceLog(choiceManager, (TrailAssignment) assignment, 1000);
	}

	private BranchingHeuristic chainFallbackHeuristic(Grounder grounder, WritableAssignment assignment, Random random, HeuristicsConfiguration heuristicsConfiguration) {
		BranchingHeuristic branchingHeuristic = BranchingHeuristicFactory.getInstance(heuristicsConfiguration, grounder, assignment, choiceManager, random);
		if (branchingHeuristic instanceof NaiveHeuristic) {
			return branchingHeuristic;
		}
		if (branchingHeuristic instanceof ChainedBranchingHeuristics && ((ChainedBranchingHeuristics)branchingHeuristic).getLastElement() instanceof NaiveHeuristic) {
			return branchingHeuristic;
		}
		return ChainedBranchingHeuristics.chainOf(branchingHeuristic, new NaiveHeuristic(choiceManager));
	}

	@Override
	protected boolean tryAdvance(Consumer<? super AnswerSet> action) {
		if (!searchState.hasBeenInitialized) {
			initializeSearch();
		} else {
			prepareForSubsequentAnswerSet();
		}
		// Try all assignments until grounder reports no more NoGoods and all of them are satisfied
		while (true) {
			// Cooperative cancellation: if the solving thread has been interrupted (e.g. a caller-imposed
			// per-solve timeout), abort the search promptly instead of running unbounded. Inert under
			// normal (non-interrupted) solving, so it does not affect results or existing callers.
			if (Thread.currentThread().isInterrupted()) {
				throw new SolverAbortedException("Solving aborted: thread interrupted.");
			}
			performanceLog.writeIfTimeForLogging(LOGGER);
			if (searchState.isSearchSpaceCompletelyExplored) {
				LOGGER.debug("Search space has been fully explored, there are no more answer-sets.");
				logStats();
				return false;
			}
			ConflictCause conflictCause = propagate();
			if (conflictCause != null) {
				LOGGER.debug("Conflict encountered, analyzing conflict.");
				learnFromConflict(conflictCause);
			} else if (assignment.didChange()) {
				LOGGER.debug("Updating grounder with new assignments and (potentially) obtaining new NoGoods.");
				grounder.updateAssignment(assignment.getNewPositiveAssignmentsIterator());
				getNoGoodsFromGrounderAndIngest();
			} else if (choose()) {
				LOGGER.debug("Did choice.");
			} else if (close()) {
				LOGGER.debug("Closed unassigned known atoms (assigning FALSE).");
			} else if (assignment.getMBTCount() == 0) {
				provideAnswerSet(action);
				return true;
			} else {
				backtrackFromMBTsRemaining();
			}
		}
	}

	private void initializeSearch() {
		// Initially, get NoGoods from grounder.
		performanceLog.initialize();
		getNoGoodsFromGrounderAndIngest();
		searchState.hasBeenInitialized = true;
	}

	/**
	 * Resets this solver for a fresh monotone (add-only) shot on the extended program state, keeping the live
	 * solver and its entire nogood store (structural nogoods and their watches are untouched — no re-ingest).
	 * The whole trail is cleared (T ← ∅), so every ordinary two-watched-literal watch is trivially valid; the
	 * previous shot's enumeration nogoods (and any foundedness-tainted resolvents, tagged TRANSIENT) are
	 * purged; the ordinary LEARNT nogoods are kept — they are classical resolvents, entailed by N_s ∪ N_l and
	 * monotone under add_F/add_C, so they stay sound across an add-only shot. The surviving fact/structural
	 * units are re-forced at dl 0 and VSIDS activity is reset. The caller passes every surviving unit nogood,
	 * since the full clear un-forced them all.
	 *
	 * <p>Retraction shots go through {@link #retractInPlace(Collection)}, which additionally drops <em>all</em>
	 * learned nogoods (any may be unsound in the reduced program).
	 */
	public void resetForNewShot(Collection<NoGood> survivingUnits) {
		resetInPlace(survivingUnits, false);
	}

	/**
	 * Resets this solver for a fresh shot after a fact retraction. As {@link #resetForNewShot(Collection)}, but
	 * additionally {@link NoGoodStore#dropAllLearnedNoGoods() drops all learned nogoods}: under retraction a
	 * previously-sound learned resolvent may no longer be entailed, so the conservative sound choice is to drop
	 * them all (no provenance needed). The caller has already pruned the grounder's cumulative unit nogoods; the
	 * surviving fact/structural units are passed in here.
	 */
	public void retractInPlace(Collection<NoGood> survivingUnits) {
		resetInPlace(survivingUnits, true);
	}

	/**
	 * The single in-place reset shared by the monotone ({@link #resetForNewShot(Collection)}) and retraction
	 * ({@link #retractInPlace(Collection)}) shot boundaries. Backjump to dl 0; purge the previous shot's
	 * enumeration / foundedness-tainted (TRANSIENT-tagged) nogoods while the trail is still populated so their
	 * dl-0 propagations are undone; {@link WritableAssignment#clear() clear the whole trail} (T ← ∅), after
	 * which every kept watch is trivially valid; optionally drop all learned nogoods; re-register the choice
	 * callbacks that {@code clear()} wiped; {@link NoGoodStore#reassertUnits(Collection) re-force the surviving
	 * units} at dl 0; and reset VSIDS activity. No re-ingest of structural nogoods is needed.
	 *
	 * @param dropAllLearned drop every learned nogood (retraction); otherwise keep the sound LEARNT ones
	 */
	private void resetInPlace(Collection<NoGood> survivingUnits, boolean dropAllLearned) {
		endedInDecisionLevelZeroConflict = false;
		if (assignment.getDecisionLevel() > 0) {
			choiceManager.backjump(0);
		}
		// AlphaInc transient nogoods N_t (Algorithm 2 tagging & Lemma 3 of "Boosting ASP by Incremental Lazy
		// Grounding"): enumeration, completion and foundedness nogoods are NOT program-monotone, so they — and
		// every learned resolvent tainted through them (tagged TRANSIENT) — must not cross the shot boundary.
		// Detach the previous shot's enumeration nogoods (and any foundedness-tainted resolvents, tagged
		// TRANSIENT) while the trail is still populated so their dl-0 propagations are undone. Unconditional:
		// a purge with no enumeration nogoods present is a no-op.
		store.purgeTransientNoGoods();
		enumerationUsed = false;
		assignment.clear();   // T ← ∅ : every ordinary watch is trivially valid after a full clear
		if (dropAllLearned) {
			store.dropAllLearnedNoGoods();
		}
		choiceManager.reset();
		store.reassertUnits(survivingUnits);
		branchingHeuristic.resetActivity();
		searchState.hasBeenInitialized = false;
		searchState.isSearchSpaceCompletelyExplored = false;
		searchState.afterAllAtomsAssigned = false;
	}

	private void prepareForSubsequentAnswerSet() {
		// We already found one Answer-Set and are requested to find another one. The dl-0 hot-start snapshot
		// was already captured when that first answer set was produced (see provideAnswerSet).
		enumerationUsed = true;
		searchState.afterAllAtomsAssigned = false;
		if (assignment.getDecisionLevel() == 0) {
			// Solver is at decision level 0 again after finding some answer-set
			searchState.isSearchSpaceCompletelyExplored = true;
			return;
		}
		// Create enumeration NoGood to avoid finding the same Answer-Set twice.
		final NoGood enumerationNoGood = choiceManager.computeEnumeration();
		final int backjumpLevel = assignment.minimumConflictLevel(enumerationNoGood);
		if (backjumpLevel == -1) {
			throw oops("Enumeration nogood is not violated");
		}
		if (backjumpLevel == 0) {
			// Search space exhausted (only happens if first choice is for TRUE at decision level 1 for an atom that was MBT at decision level 0 already).
			searchState.isSearchSpaceCompletelyExplored = true;
			return;
		}
		// Backjump instead of backtrackSlow, enumerationNoGood will invert last choice.
		choiceManager.backjump(backjumpLevel - 1);
		LOGGER.debug("Adding enumeration nogood: {}", enumerationNoGood);
		if (!addAndBackjumpIfNecessary(grounder.register(enumerationNoGood), enumerationNoGood, Integer.MAX_VALUE)) {
			searchState.isSearchSpaceCompletelyExplored = true;
		}
	}

	private void getNoGoodsFromGrounderAndIngest() {
		Map<Integer, NoGood> obtained = grounder.getNoGoods(assignment);
		if (!ingest(obtained)) {
			searchState.isSearchSpaceCompletelyExplored = true;
		}
	}

	private void learnFromConflict(ConflictCause conflictCause) {
		LOGGER.debug("Violating assignment is: {}", assignment);
		Antecedent conflictAntecedent = conflictCause.getAntecedent();
		NoGood violatedNoGood = new NoGood(conflictAntecedent.getReasonLiterals().clone());
		// TODO: The violatedNoGood should not be necessary here, but this requires major type changes in heuristics.
		branchingHeuristic.violatedNoGood(violatedNoGood);
		if (searchState.afterAllAtomsAssigned) {
			LOGGER.debug("Assignment is violated after all unassigned atoms have been assigned false.");
			conflictsAfterClosing++;
			if (!treatConflictAfterClosing(conflictAntecedent)) {
				searchState.isSearchSpaceCompletelyExplored = true;
			}
			searchState.afterAllAtomsAssigned = false;
		} else {
			if (!learnBackjumpAddFromConflict(conflictCause)) {
				searchState.isSearchSpaceCompletelyExplored = true;
			}
		}
	}

	private ConflictCause propagate() {
		LOGGER.trace("Doing propagation step.");
		ConflictCause conflictCause = store.propagate();
		LOGGER.trace("Assignment after propagation is: {}", assignment);
		if (!disableNoGoodDeletion && conflictCause == null) {
			// Run learned-NoGood deletion-strategy.
			store.cleanupLearnedNoGoods();
		}
		return conflictCause;
	}

	private void provideAnswerSet(Consumer<? super AnswerSet> action) {
		// NOTE: If we would do optimization, we would now have a guaranteed upper bound.
		AnswerSet as = translate(assignment.getTrueAssignments());
		LOGGER.debug("Answer-Set found: {}", as);
		action.accept(as);
		logStats();
	}

	private void backtrackFromMBTsRemaining() {
		LOGGER.debug("Backtracking from wrong choices ({} MBTs).", assignment.getMBTCount());
		searchState.afterAllAtomsAssigned = false;
		if (!justifyMbtAndBacktrack()) {
			searchState.isSearchSpaceCompletelyExplored = true;
		}
	}

	/**
	 * Adds a noGood to the store and in case of out-of-order literals causing another conflict, triggers further backjumping.
	 * @param noGoodId the unique identifier of the NoGood to add.
	 * @param noGood the NoGood to add.
	 * @param lbd the LBD (literal blocks distance) value of the NoGood.
	 */
	private boolean addAndBackjumpIfNecessary(int noGoodId, NoGood noGood, int lbd) {
		while (store.add(noGoodId, noGood, lbd) != null) {
			LOGGER.debug("Adding noGood (again) caused conflict, computing real backjumping level now.");
			int backjumpLevel = learner.computeConflictFreeBackjumpingLevel(noGood);
			if (backjumpLevel < 0) {
				return false;
			}
			choiceManager.backjump(backjumpLevel);
			if (store.propagate() != null) {
				throw  oops("Violated NoGood after backtracking.");
			}
		}
		return true;
	}

	/**
	 * Analyzes the conflict and learns a new NoGood (causing backjumping and addition to the NoGood store).
	 *
	 * @return false iff the analysis result shows that the set of NoGoods is unsatisfiable.
	 */
	private boolean learnBackjumpAddFromConflict(ConflictCause conflictCause) {
		GroundConflictNoGoodLearner.ConflictAnalysisResult analysisResult = learner.analyzeConflictingNoGood(conflictCause.getAntecedent());

		LOGGER.debug("Analysis result: {}", analysisResult);
		if (analysisResult == UNSAT) {
			// Halt if unsatisfiable. Terminal conflict is at decision level 0 (analyzeConflict returns UNSAT
			// only then), so the session must rebuild rather than warm-reset before the next shot.
			endedInDecisionLevelZeroConflict = true;
			return false;
		}

		branchingHeuristic.analyzedConflict(analysisResult);

		if (analysisResult.learnedNoGood == null) {
			throw oops("Did not learn new NoGood from conflict.");
		}

		choiceManager.backjump(analysisResult.backjumpLevel);
		NoGood learnedNoGood = analysisResult.learnedNoGood;
		if (analysisResult.transientDerived) {
			// Conflict analysis resolved through a transient nogood, so this resolvent is sound only for the
			// shot's temporarily-restricted program. Register it as transient so it is purged with N_t at
			// the shot boundary instead of persisting unsoundly in the learned-nogood store across shots.
			learnedNoGood = learnedNoGood.asTransient();
		}
		int noGoodId = grounder.register(learnedNoGood);
		return addAndBackjumpIfNecessary(noGoodId, learnedNoGood, analysisResult.lbd);
	}

	private boolean justifyMbtAndBacktrack() {
		mbtAtFixpoint++;
		// Run justification only if enabled and possible.
		if (disableJustifications || !(grounder instanceof ProgramAnalyzingGrounder)) {
			if (!backtrack()) {
				logStats();
				return false;
			}
			return true;
		}
		ProgramAnalyzingGrounder analyzingGrounder = (ProgramAnalyzingGrounder) grounder;
		// Justify one MBT assigned atom.
		int atomToJustify = assignment.getBasicAtomAssignedMBT();
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Searching for justification of {} / {}", atomToJustify, atomStore.atomToString(atomToJustify));
			LOGGER.debug("Assignment is (TRUE part only): {}", translate(assignment.getTrueAssignments()));
		}
		Set<Literal> reasonsForUnjustified = analyzingGrounder.justifyAtom(atomToJustify, assignment);
		NoGood noGood = noGoodFromJustificationReasons(atomToJustify, reasonsForUnjustified);

		int noGoodID = grounder.register(noGood);
		Map<Integer, NoGood> obtained = new LinkedHashMap<>();
		obtained.put(noGoodID, noGood);
		LOGGER.debug("Learned NoGood is: {}", atomStore.noGoodToString(noGood));
		// Add NoGood and trigger backjumping.
		if (!ingest(obtained)) {
			logStats();
			return false;
		}
		return true;
	}

	private NoGood noGoodFromJustificationReasons(int atomToJustify, Set<Literal> reasonsForUnjustified) {
		// Turn the justification into a NoGood.
		int[] reasons = new int[reasonsForUnjustified.size() + 1];
		reasons[0] = atomToLiteral(atomToJustify);
		int arrpos = 1;
		for (Literal literal : reasonsForUnjustified) {
			reasons[arrpos++] = atomToLiteral(atomStore.get(literal.getAtom()), !literal.isNegated());
		}
		// DESIGN (B) EXPERIMENT: tag foundedness (justification) nogoods as TRANSIENT so they — and, via the
		// existing transient taint (Antecedent.fromTransient -> ConflictAnalysisResult.transientDerived ->
		// asTransient), every learned nogood that resolves through one — are dropped by purgeTransientNoGoods
		// at the contaminated shot boundary, while ordinary LEARNT nogoods are KEPT. This tests whether we can
		// preserve the sound learned nogoods (drop only the foundedness-tainted subset) instead of dropping N_l
		// wholesale. In batch (single shot) nothing is ever purged, so the tag only affects lifecycle.
		return NoGood.transientNoGood(reasons);
	}

	private boolean treatConflictAfterClosing(Antecedent violatedNoGood) {
		if (disableJustificationAfterClosing || disableJustifications || !(grounder instanceof ProgramAnalyzingGrounder)) {
			// Will not learn from violated NoGood, do simple backtrack.
			LOGGER.debug("NoGood was violated after all unassigned atoms were assigned to false; will not learn from it; skipping.");
			if (!backtrack()) {
				logStats();
				return false;
			}
			return true;
		}
		ProgramAnalyzingGrounder analyzingGrounder = (ProgramAnalyzingGrounder) grounder;
		LOGGER.debug("Justifying atoms in violated nogood.");
		LinkedHashSet<Integer> toJustify = new LinkedHashSet<>();
		// Find those literals in violatedNoGood that were just assigned false.
		for (Integer literal : violatedNoGood.getReasonLiterals()) {
			if (assignment.getImpliedBy(atomOf(literal)) == TrailAssignment.CLOSING_INDICATOR_ANTECEDENT) {
				toJustify.add(literal);
			}
		}
		// Since the violatedNoGood may contain atoms other than BasicAtom, these have to be treated.
		Map<Integer, NoGood> obtained = new LinkedHashMap<>();
		Iterator<Integer> toJustifyIterator = toJustify.iterator();
		ArrayList<Integer> ruleAtomReplacements = new ArrayList<>();
		while (toJustifyIterator.hasNext()) {
			Integer literal = toJustifyIterator.next();
			Atom atom = atomStore.get(atomOf(literal));
			if (atom instanceof BasicAtom) {
				continue;
			}
			if (!(atom instanceof RuleAtom)) {
				// Ignore atoms other than RuleAtom.
				toJustifyIterator.remove();
				continue;
			}
			// For RuleAtoms in toJustify the corresponding ground body contains BasicAtoms that have been assigned FALSE in the closing.
			// First, get NonGroundRule + Substitution, stored in the RuleAtom's single term.
			RuleAtom.RuleAtomData ruleAtomData = (RuleAtom.RuleAtomData) ((ConstantTerm<?>)(atom.getTerms().get(0))).getObject();
			Substitution groundingSubstitution = ruleAtomData.getSubstitution();
			CompiledRule nonGroundRule = ruleAtomData.getNonGroundRule();
			// Find ground literals in the body that have been assigned false and justify those.
			for (Literal bodyLiteral : nonGroundRule.getBody()) {
				Atom groundAtom = bodyLiteral.getAtom().substitute(groundingSubstitution);
				if (groundAtom instanceof ComparisonAtom || analyzingGrounder.isFact(groundAtom)) {
					// Facts and ComparisonAtoms are always true, no justification needed.
					continue;
				}
				int groundAtomId = atomStore.get(groundAtom);
				Antecedent impliedBy = assignment.getImpliedBy(groundAtomId);
				// Check if atom was assigned to FALSE during the closing.
				if (impliedBy == TrailAssignment.CLOSING_INDICATOR_ANTECEDENT) {
					ruleAtomReplacements.add(atomToNegatedLiteral(groundAtomId));
				}
			}
			toJustifyIterator.remove();
		}
		toJustify.addAll(ruleAtomReplacements);
		for (Integer literalToJustify : toJustify) {
			LOGGER.debug("Searching for justification(s) of {} / {}", toJustify, atomStore.atomToString(atomOf(literalToJustify)));
			Set<Literal> reasonsForUnjustified = analyzingGrounder.justifyAtom(atomOf(literalToJustify), assignment);
			NoGood noGood = noGoodFromJustificationReasons(atomOf(literalToJustify), reasonsForUnjustified);
			int noGoodID = grounder.register(noGood);
			obtained.put(noGoodID, noGood);
			LOGGER.debug("Learned NoGood is: {}", atomStore.noGoodToString(noGood));
		}
		// Backtrack to remove the violation.
		if (!backtrack()) {
			logStats();
			return false;
		}
		// Add newly obtained noGoods.
		if (!ingest(obtained)) {
			logStats();
			return false;
		}
		return true;
	}

	private boolean close() {
		searchState.afterAllAtomsAssigned = true;
		return assignment.closeUnassignedAtoms();
	}

	/**
	 * Realizes chronological backtracking.
	 *
	 * @return {@code true} iff it is possible to backtrack even further, {@code false} otherwise
	 */
	private boolean backtrack() {
		while (assignment.getDecisionLevel() != 0) {
			// Backtrack highest decision level.
			final int previousDecisionLevel = assignment.getDecisionLevel();
			final Choice backtrackedChoice = choiceManager.backtrack();
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Backtracked choice atom is {}={}@{}.", backtrackedChoice.getAtom(),
					backtrackedChoice.getTruthValue() ? ThriceTruth.TRUE : ThriceTruth.FALSE, previousDecisionLevel);
			}

			// Construct inverse choice, if choice can be inverted.
			final Choice invertedChoice = Choice.getInverted(backtrackedChoice);
			if (invertedChoice == null) {
				LOGGER.debug("Backtracking further, because last choice was already backtracked/inverted.");
				continue;
			}
			// Choose inverse as long as the choice atom is not assigned.
			ThriceTruth currentTruthValue = assignment.getTruth(backtrackedChoice.getAtom());
			if (currentTruthValue == null) {
				LOGGER.debug("Choosing inverse, choice is: {}.", invertedChoice);
				choiceManager.choose(invertedChoice);
				break;
			}
			LOGGER.debug("Backtracking further, not inverting choice, because its value is implied now.");
		}
		return assignment.getDecisionLevel() != 0;
	}

	private boolean ingest(Map<Integer, NoGood> obtained) {
		assignment.growForMaxAtomId();
		int maxAtomId = atomStore.getMaxAtomId();
		store.growForMaxAtomId(maxAtomId);
		choiceManager.growForMaxAtomId(maxAtomId);
		branchingHeuristic.growForMaxAtomId(maxAtomId);
		branchingHeuristic.newNoGoods(obtained.values());

		LinkedList<Map.Entry<Integer, NoGood>> noGoodsToAdd = new LinkedList<>(obtained.entrySet());
		Map.Entry<Integer, NoGood> entry;
		while ((entry = noGoodsToAdd.poll()) != null) {
			if (NoGood.UNSAT.equals(entry.getValue())) {
				// Empty NoGood cannot be satisfied, program is unsatisfiable (unconditionally, i.e. at dl 0).
				endedInDecisionLevelZeroConflict = true;
				return false;
			}

			final ConflictCause conflictCause = store.add(entry.getKey(), entry.getValue(), Integer.MAX_VALUE);
			if (conflictCause != null && !fixContradiction(entry, conflictCause)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Attempts to fix a given conflict that arose from adding a nogood.
	 * @param noGoodEntry the description of the NoGood that caused the conflict.
	 * @param conflictCause a description of the cause of the conflict.
	 * @return true if the contradiction could be resolved (by backjumping) and the NoGood was added.
	 * 	   False otherwise, i.e., iff the program is UNSAT.
	 */
	private boolean fixContradiction(Map.Entry<Integer, NoGood> noGoodEntry, ConflictCause conflictCause) {
		LOGGER.debug("Attempting to fix violation of {} caused by {}", noGoodEntry.getValue(), conflictCause);

		GroundConflictNoGoodLearner.ConflictAnalysisResult conflictAnalysisResult = learner.analyzeConflictFromAddingNoGood(conflictCause.getAntecedent());
		if (conflictAnalysisResult == UNSAT) {
			// Adding a nogood contradicted the decision-level-0 assignment (e.g. a fact/constraint conflicting
			// at the root). Mark the shot so the session rebuilds rather than warm-resets before the next.
			endedInDecisionLevelZeroConflict = true;
			return false;
		}
		branchingHeuristic.analyzedConflict(conflictAnalysisResult);
		if (conflictAnalysisResult.learnedNoGood != null) {
			throw oops("Unexpectedly learned NoGood after addition of new NoGood caused a conflict.");
		}

		choiceManager.backjump(conflictAnalysisResult.backjumpLevel);

		// If NoGood was learned, add it to the store.
		// Note that the learned NoGood may cause further conflicts, since propagation on lower decision levels is lazy,
		// hence backtracking once might not be enough to remove the real conflict cause.
		return addAndBackjumpIfNecessary(noGoodEntry.getKey(), noGoodEntry.getValue(), LBD_NO_VALUE);
	}

	private boolean choose() {
		choiceManager.addChoiceInformation(grounder.getChoiceAtoms(), grounder.getHeadsToBodies());
		choiceManager.updateAssignments();

		// Hint: for custom heuristics, evaluate them here and pick a value if the heuristics suggests one.
		int literal;
		if ((literal = branchingHeuristic.chooseLiteral()) == DEFAULT_CHOICE_LITERAL) {
			LOGGER.debug("No choices!");
			return false;
		} else if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Branching heuristic chose literal {}", atomStore.literalToString(literal));
		}

		choiceManager.choose(new Choice(literal, false));
		return true;
	}
	
	@Override
	public int getNumberOfChoices() {
		return choiceManager.getChoices();
	}

	@Override
	public int getNumberOfBacktracks() {
		return choiceManager.getBacktracks();
	}

	@Override
	public int getNumberOfBacktracksWithinBackjumps() {
		return choiceManager.getBacktracksWithinBackjumps();
	}

	@Override
	public int getNumberOfBackjumps() {
		return choiceManager.getBackjumps();
	}

	@Override
	public int getNumberOfBacktracksDueToRemnantMBTs() {
		return mbtAtFixpoint;
	}

	@Override
	public int getNumberOfConflictsAfterClosing() {
		return conflictsAfterClosing;
	}

	@Override
	public int getNumberOfDeletedNoGoods() {
		if (!(store instanceof NoGoodStoreAlphaRoaming)) {
			return 0;
		}
		return ((NoGoodStoreAlphaRoaming)store).getLearnedNoGoodDeletion().getNumberOfDeletedNoGoods();
	}

	public NoGoodCounter getNoGoodCounter() {
		return store.getNoGoodCounter();
	}

	private void logStats() {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(getStatisticsString());
			if (branchingHeuristic instanceof ChainedBranchingHeuristics) {
				LOGGER.debug("Decisions made by each heuristic:");
				for (Entry<BranchingHeuristic, Integer> heuristicToDecisionCounter : ((ChainedBranchingHeuristics)branchingHeuristic).getNumberOfDecisions().entrySet()) {
					LOGGER.debug("{}: {}", heuristicToDecisionCounter.getKey(), heuristicToDecisionCounter.getValue());
				}
			}
			NoGoodCounter noGoodCounter = store.getNoGoodCounter();
			LOGGER.debug("Number of NoGoods by type: {}", noGoodCounter.getStatsByType());
			LOGGER.debug("Number of NoGoods by cardinality: {}", noGoodCounter.getStatsByCardinality());
			AtomCounter atomCounter = atomStore.getAtomCounter();
			LOGGER.debug("Number of atoms by type: {}", atomCounter.getStatsByType());
		}
	}
}
