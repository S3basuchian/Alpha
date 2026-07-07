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

	/**
	 * Experiment flag ({@code -Dalpha.resetVsidsPerShot=true}, off by default): cold-reset the branching
	 * heuristic's activity at every shot boundary. Tests whether warm-start VSIDS anchoring is what makes
	 * incremental solving blow up after an edit that invalidates the previous shot's answer set.
	 */
	private static final boolean RESET_VSIDS_PER_SHOT = Boolean.getBoolean("alpha.resetVsidsPerShot");

	/**
	 * Experiment flag ({@code -Dalpha.foundednessResetKeepsVsids=true}, off by default): when set,
	 * {@link #retractInPlaceKeepSoundLearned(Collection)} keeps warm VSIDS (skips {@code resetActivity()}),
	 * matching the retract path's default. The foundedness T-reset then only clears the trail + purges the
	 * foundedness-tainted nogoods, leaving branching activity carried across the shot. Lets us A/B whether the
	 * VSIDS wipe — rather than the T-clear re-propagation — is what the foundedness reset costs on a workload.
	 */
	private static final boolean FOUNDEDNESS_RESET_KEEPS_VSIDS = Boolean.getBoolean("alpha.foundednessResetKeepsVsids");

	/**
	 * Adaptive cold-restart ({@code -Dalpha.adaptiveVsidsReset=true}, off by default): a domain-independent,
	 * reactive alternative to {@link #RESET_VSIDS_PER_SHOT}. Warm VSIDS is kept by default; a shot that burns
	 * an anomalous number of decisions (relative to the recent per-shot baseline) is taken as a sign that the
	 * carried heuristic is anchored to a now-invalidated model, and the search is restarted <em>cold</em> once
	 * (backjump to dl 0 + {@link BranchingHeuristic#resetActivity()}). It never fires on the base solve (no
	 * baseline yet) nor on UNSAT shots (no answer set → no anchoring); the baseline auto-scales, so a
	 * legitimately hard instance does not false-trigger. Sound/complete: a single cold restart per shot is a
	 * standard restart (learned nogoods retained) plus an activity wipe, which only reorders branching.
	 */
	private static final boolean ADAPTIVE_VSIDS_RESET = Boolean.getBoolean("alpha.adaptiveVsidsReset");
	/** Experiment: on a cold restart, randomize the variable order instead of zeroing activity (ordering test). */
	private static final boolean COLD_RESTART_RANDOMIZE = Boolean.getBoolean("alpha.coldRestartRandomize");
	/** Experiment: allow the cold restart to fire repeatedly within a shot (randomized-restart portfolio). */
	private static final boolean COLD_RESTART_REPEAT = Boolean.getBoolean("alpha.coldRestartRepeat");
	/** Minimum decisions a shot may burn before an adaptive cold restart is allowed (absolute floor). */
	private static final long COOL_MIN_DECISIONS = Long.getLong("alpha.coolMinDecisions", 50_000L);
	/** A shot is deemed to be thrashing once it exceeds this multiple of the recent per-shot decision baseline. */
	private static final long COOL_MULTIPLIER = Long.getLong("alpha.coolMultiplier", 30L);

	private long decisionsAtShotStart;
	private long warmBaselineDecisions;   // EMA of decisions used by clean (non-cooled) shots
	private int cleanShotsCompleted;      // shots that produced an answer set without needing a cold restart
	private boolean cooledThisShot;

	private int mbtAtFixpoint;
	private int conflictsAfterClosing;
	private final boolean disableJustifications;
	private boolean disableJustificationAfterClosing = !Boolean.getBoolean("alpha.enableJustificationAfterClosing");	// Keep disabled for now, case not fully worked out yet.
	private final boolean disableNoGoodDeletion;

	/**
	 * Set to {@code true} when this shot's search learned a justification (foundedness) nogood — via
	 * {@link #justifyMbtAndBacktrack()} or {@link #treatConflictAfterClosing(Antecedent)}. Such a nogood
	 * (and any learned nogood that resolved through it, plus any dl-0 atom it forced) is sound only for the
	 * program at this shot: adding a fact in a later shot can <em>found</em> a previously-unfounded atom, so
	 * a surviving "atom is unfounded" nogood or its dl-0 effect would spuriously block the new answer set
	 * (the dual of the retraction case). The session reads this via
	 * {@link #hasLearnedJustificationNoGoodThisShot()} and, when set, routes the next monotone reset through
	 * the conservative clear+reassert ({@link #retractInPlace(Collection)}) instead of the snapshot-reusing
	 * warm {@link #resetForNewShot()}, so nothing foundedness-derived survives. Reset at each shot boundary.
	 */
	private boolean justificationLearnedThisShot = false;
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
	 * store. {@link #resetForNewShot()} reads this to decide whether to invoke
	 * {@link NoGoodStore#purgeEnumerationNoGoods()} during reset. Cleared again at the end of reset.
	 */
	private boolean enumerationUsed = false;

	// dl-0 hot-start snapshot captured at this shot's first answer set, before any enumeration nogood is
	// added. {@link #resetForNewShot()} rewinds dl 0 to it between shots — a single rewind that drops the
	// answer set's closing atoms together with every enumeration-forced/-derived dl-0 atom. Null until the
	// first answer set of a shot is produced (and stays null for a shot that finds none).
	private Map<Integer, ThriceTruth> dl0Snapshot;

	/**
	 * Set to {@code true} when this shot's search terminated in a conflict at decision level 0 — an UNSAT
	 * proven at the root (an empty nogood, or conflict analysis that resolved to dl 0). Such a shot leaves no
	 * consistent dl-0 fixpoint and may have short-circuited grounding, so the incremental resets
	 * ({@link #resetForNewShot()} / {@link #retractInPlace(Collection)}) cannot soundly repair it and the
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

	/**
	 * @return whether the last shot learned a justification (foundedness) nogood (see
	 *         {@link #justificationLearnedThisShot}). The session uses this to route the next monotone reset
	 *         through the conservative clear+reassert instead of the snapshot-reusing warm reset.
	 */
	public boolean hasLearnedJustificationNoGoodThisShot() {
		return justificationLearnedThisShot;
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
			if (ADAPTIVE_VSIDS_RESET && cleanShotsCompleted > 0 && (!cooledThisShot || COLD_RESTART_REPEAT)) {
				long budget = Math.max(COOL_MIN_DECISIONS, COOL_MULTIPLIER * warmBaselineDecisions);
				if (choiceManager.getChoices() - decisionsAtShotStart > budget) {
					coldRestart();
					if (COLD_RESTART_REPEAT) {
						decisionsAtShotStart = choiceManager.getChoices(); // re-arm the budget window for the next restart
					}
				}
			}
			performanceLog.writeIfTimeForLogging(LOGGER);
			if (searchState.isSearchSpaceCompletelyExplored) {
				LOGGER.debug("Search space has been fully explored, there are no more answer-sets.");
				logStats();
				return false;
			}
			boolean diag = Boolean.getBoolean("alpha.diagLoop");
			long diagT0 = diag ? System.nanoTime() : 0;
			ConflictCause conflictCause = propagate();
			if (diag) {
				diagTProp += System.nanoTime() - diagT0;
				diagLoopIters++;
				if (diagLoopIters % 100_000 == 0) {
					NoGoodCounter cnt = store.getNoGoodCounter();
					LOGGER.info("DIAG-LOOP shot#{} iters={} confl={} conflAfterClosing={} learnt={} static={} naryNG={} maxBackjumpTo={}",
							diagShotCounter, diagLoopIters, diagNConfl, conflictsAfterClosing,
							cnt.getNumberOfNoGoods(at.ac.tuwien.kr.alpha.core.common.NoGoodInterface.Type.LEARNT),
							cnt.getNumberOfNoGoods(at.ac.tuwien.kr.alpha.core.common.NoGoodInterface.Type.STATIC),
							cnt.getNumberOfNAryNoGoods(), diagMaxBackjumpTarget);
				}
			}
			long diagT1 = diag ? System.nanoTime() : 0;
			if (conflictCause != null) {
				LOGGER.debug("Conflict encountered, analyzing conflict.");
				learnFromConflict(conflictCause);
				if (diag) { diagNConfl++; }
			} else if (assignment.didChange()) {
				LOGGER.debug("Updating grounder with new assignments and (potentially) obtaining new NoGoods.");
				if (Boolean.getBoolean("alpha.continuousReReport")) {
					// Experiment: re-feed ALL positive atoms to the grounder each step (not just newly-assigned),
					// to test whether the "newly-added only" feeding is why constraints for the current colouring
					// are never grounded.
					assignment.rewindNewAssignmentsPointer();
				}
				grounder.updateAssignment(assignment.getNewPositiveAssignmentsIterator());
				getNoGoodsFromGrounderAndIngest();
				if (diag) { diagNGround++; }
			} else if (choose()) {
				LOGGER.debug("Did choice.");
				if (diag) { diagNChoose++; }
			} else if (close()) {
				LOGGER.debug("Closed unassigned known atoms (assigning FALSE).");
				if (diag) { diagNClose++; }
			} else if (assignment.getMBTCount() == 0) {
				provideAnswerSet(action);
				return true;
			} else {
				backtrackFromMBTsRemaining();
				if (diag) { diagNMbt++; }
			}
			if (diag) { diagTOther += System.nanoTime() - diagT1; }
		}
	}

	private int diagShotCounter = 0;
	private int diagChoiceCount = 0;
	private long diagConflictCount = 0;
	private int diagMaxBackjumpTarget = 0;
	private long diagBackjumpTargetSum = 0;
	private long diagDlBeforeSum = 0;
	private long diagLoopIters, diagTProp, diagTOther, diagNConfl, diagNGround, diagNChoose, diagNClose, diagNMbt;

	private void initializeSearch() {
		// Initially, get NoGoods from grounder.
		performanceLog.initialize();
		if (Boolean.getBoolean("alpha.reReportAllPositive")) {
			// Experiment: re-feed ALL currently-positive atoms to the grounder (not just those newly assigned
			// this shot), to test whether the persistent hot-start colouring's constraints go ungrounded
			// because those atoms were already reported to the grounder in a prior shot.
			assignment.rewindNewAssignmentsPointer();
			grounder.updateAssignment(assignment.getNewPositiveAssignmentsIterator());
		}
		getNoGoodsFromGrounderAndIngest();
		diagShotCounter++;
		diagChoiceCount = 0;
		diagConflictCount = 0;
		diagMaxBackjumpTarget = 0;
		diagBackjumpTargetSum = 0;
		diagDlBeforeSum = 0;
		diagLoopIters = diagTProp = diagTOther = diagNConfl = diagNGround = diagNChoose = diagNClose = diagNMbt = 0;
		if (Boolean.getBoolean("alpha.diagShotState")) {
			NoGoodCounter c = store.getNoGoodCounter();
			LOGGER.info("DIAG shot#{} solveStart: atoms={} choicePoints={} unaryNG={} binaryNG={} naryNG={}",
					diagShotCounter, atomStore.getMaxAtomId(), choiceManager.getNumberOfChoicePoints(),
					c.getNumberOfUnaryNoGoods(), c.getNumberOfBinaryNoGoods(), c.getNumberOfNAryNoGoods());
		}
		searchState.hasBeenInitialized = true;
		// Snapshot the decision counter for this shot's adaptive cold-restart budget.
		decisionsAtShotStart = choiceManager.getChoices();
		cooledThisShot = false;
	}

	/**
	 * Restart the current shot's search from decision level 0 with a cold branching heuristic: unwind all
	 * choices and wipe accumulated activity, so a warm start anchored to a now-invalidated model stops
	 * mis-guiding the search. Learned nogoods are retained (this is a normal restart), and it happens at most
	 * once per shot, so soundness and completeness are preserved.
	 */
	private void coldRestart() {
		LOGGER.debug("Adaptive cold restart: shot exceeded decision budget, wiping heuristic activity.");
		if (assignment.getDecisionLevel() > 0) {
			choiceManager.backjump(0);
		}
		if (COLD_RESTART_RANDOMIZE) {
			branchingHeuristic.randomizeActivity();
		} else {
			branchingHeuristic.resetActivity();
		}
		searchState.afterAllAtomsAssigned = false;
		cooledThisShot = true;
	}

	/**
	 * Resets this solver for a fresh enumeration on (potentially extended) program state. Preserves the
	 * {@link NoGoodStore}'s structural and learned nogoods plus the branching heuristic's activity scores,
	 * and the atom-store / grounder coupling.  Discards the choice stack and the {@code dl &gt; 0}
	 * portion of the assignment by backjumping to decision level 0; if the previous shot added
	 * enumeration nogoods to the store, those are purged via {@link NoGoodStore#purgeEnumerationNoGoods()}.
	 * Then dl 0 is cleaned of the previous shot's residue: if that shot produced an answer set, dl 0 is
	 * rewound to the snapshot captured at its first answer set ({@link #dl0Snapshot}) — a single hot-start
	 * restore that drops the answer set's closing atoms together with every enumeration-forced/-derived dl-0
	 * atom; if it produced none (UNSAT), there is no snapshot, so any dl-0 closing atoms left by an UNSAT
	 * closure are stripped directly via {@link WritableAssignment#unassignClosingAssignmentsAtDecisionLevelZero()}.
	 * The search-state flags are reset so the next call to {@link #tryAdvance} runs {@link #initializeSearch()}
	 * again — which pulls any newly-derived nogoods from the grounder and ingests them into the existing store.
	 *
	 * <p>Only monotone (add-only) shots reach this reset. Retraction shots go through
	 * {@link #retractInPlace(Collection)} instead, which additionally clears the trail and drops learned
	 * nogoods. Learned nogoods and VSIDS are always preserved here.
	 */
	public void resetForNewShot() {
		endedInDecisionLevelZeroConflict = false;
		justificationLearnedThisShot = false;
		if (assignment.getDecisionLevel() > 0) {
			choiceManager.backjump(0);
		}
		if (enumerationUsed) {
			// This shot enumerated: detach its enumeration nogoods from the store.
			store.purgeEnumerationNoGoods();
			enumerationUsed = false;
		}
		if (dl0Snapshot != null) {
			// This shot found an answer set (single or enumerated): hot-start dl 0 from the snapshot captured
			// at its first answer set — one rewind that drops the answer set's closing atoms AND every
			// enumeration-forced/-derived dl-0 atom.
			assignment.restoreToDl0Snapshot(dl0Snapshot);
			dl0Snapshot = null;
		} else {
			// No answer set this shot (UNSAT): there is no snapshot to restore from, but an UNSAT closure may
			// still have left dl-0 closing atoms — strip them, else a later fact deriving a closed atom TRUE
			// would conflict at dl 0 and spuriously report UNSAT.
			assignment.unassignClosingAssignmentsAtDecisionLevelZero();
		}
		if (RESET_VSIDS_PER_SHOT) {
			branchingHeuristic.resetActivity();
		}
		searchState.hasBeenInitialized = false;
		searchState.isSearchSpaceCompletelyExplored = false;
		searchState.afterAllAtomsAssigned = false;
	}

	/**
	 * Resets this solver for a fresh shot after a fact retraction, <em>keeping the live solver and its
	 * entire nogood store</em> (structural nogoods and their watches are untouched — no re-ingest). The
	 * caller has already pruned the grounder's cumulative unit nogoods; the surviving fact/structural
	 * units are passed in here.
	 *
	 * <p>Steps: (1) backjump to dl 0 and purge the previous shot's enumeration nogoods; (2)
	 * {@link WritableAssignment#clear() clear the whole trail} — every literal becomes unassigned, so every
	 * ordinary two-watched-literal watch is trivially valid and the kept structural nogoods stay sound;
	 * (3) {@link NoGoodStore#dropAllLearnedNoGoods() drop all learned nogoods} (any may be unsound in the
	 * reduced program — the sound conservative choice, no provenance needed); (4) re-register the choice
	 * callbacks that {@code clear()} wiped; (5) {@link NoGoodStore#reassertUnits(Collection) re-force the
	 * surviving units} at dl 0, which re-triggers propagation of their structural consequences on the next
	 * solve. VSIDS is preserved for free (this is the same solver object) unless {@code resetHeuristicActivity}
	 * is set.
	 */
	public void retractInPlace(Collection<NoGood> survivingUnits) {
		retractInPlace(survivingUnits, RESET_VSIDS_PER_SHOT);
	}

	/**
	 * As {@link #retractInPlace(Collection)}, but additionally resets the branching heuristic's activity when
	 * {@code resetHeuristicActivity} is set. The foundedness-contaminated monotone path passes {@code true}:
	 * that path fires on a shot whose predecessor learned justification nogoods (e.g. every graph-coloring
	 * shot), and the carried VSIDS activity is anchored to the previous shot's model — after a structural
	 * change (a new edge) it mis-guides the search into a multi-minute thrash on an instance a fresh solve
	 * finishes in milliseconds. Resetting activity there recovers the fresh-solve behaviour; benchmarks that
	 * never learn justification nogoods keep warm VSIDS and are unaffected.
	 */
	public void retractInPlace(Collection<NoGood> survivingUnits, boolean resetHeuristicActivity) {
		endedInDecisionLevelZeroConflict = false;
		justificationLearnedThisShot = false;
		if (assignment.getDecisionLevel() > 0) {
			choiceManager.backjump(0);
		}
		// Detach the previous shot's enumeration nogoods while the trail is still populated.
		if (enumerationUsed) {
			store.purgeEnumerationNoGoods();
			enumerationUsed = false;
		}
		// Clear the whole trail (also wipes per-atom change callbacks, re-registered by choiceManager.reset).
		// The pre-retraction dl-0 snapshot describes that now-cleared trail, so drop it — the retraction
		// shot re-captures a fresh one at its own first answer set.
		dl0Snapshot = null;
		assignment.clear();
		// Drop all learned nogoods — the assignment is already clear, so this only detaches watches/counters.
		store.dropAllLearnedNoGoods();
		choiceManager.reset();
		// Re-force the surviving units at dl 0; propagation of their consequences runs on the next solve.
		store.reassertUnits(survivingUnits);
		if (resetHeuristicActivity) {
			branchingHeuristic.resetActivity();
		}
		searchState.hasBeenInitialized = false;
		searchState.isSearchSpaceCompletelyExplored = false;
		searchState.afterAllAtomsAssigned = false;
	}

	/**
	 * DESIGN (B) EXPERIMENT: like {@link #retractInPlace(Collection, boolean)} (full trail clear T ← ∅ + reset
	 * VSIDS), but instead of dropping <em>all</em> learned nogoods it drops <em>only the foundedness-tainted
	 * subset</em> — the justification nogoods (tagged ENUMERATION) and every learned nogood that resolved
	 * through one (also ENUMERATION via the taint) — via {@link NoGoodStore#purgeEnumerationNoGoods()}, while
	 * KEEPING the ordinary LEARNT nogoods for cross-shot reuse. Tests whether N_l must be reset wholesale or
	 * only its non-monotone (foundedness) part. Full T clear makes this need only the nogood-resolution taint,
	 * not a dl-0 assignment taint.
	 */
	public void retractInPlaceKeepSoundLearned(Collection<NoGood> survivingUnits) {
		endedInDecisionLevelZeroConflict = false;
		justificationLearnedThisShot = false;
		if (assignment.getDecisionLevel() > 0) {
			choiceManager.backjump(0);
		}
		// Drop the foundedness-tainted (ENUMERATION-typed) nogoods while the trail is still populated so their
		// dl-0 propagations are undone. Unconditional (not gated on enumerationUsed): foundedness nogoods are
		// tagged ENUMERATION and may be present without any actual answer-set enumeration having occurred.
		store.purgeEnumerationNoGoods();
		enumerationUsed = false;
		dl0Snapshot = null;
		assignment.clear();   // T ← ∅
		// Deliberately DO NOT dropAllLearnedNoGoods: the ordinary LEARNT nogoods are classical resolvents,
		// entailed by N_s ∪ N_l and monotone under add_F/add_C, so they stay sound. Only the foundedness-tainted
		// subset was purged above. After the full clear every kept watch is trivially valid.
		choiceManager.reset();
		store.reassertUnits(survivingUnits);
		if (!FOUNDEDNESS_RESET_KEEPS_VSIDS) {
			branchingHeuristic.resetActivity();
		}
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

	private static final java.util.concurrent.atomic.AtomicInteger DIAG_SESSION_COUNTER = new java.util.concurrent.atomic.AtomicInteger();
	private final int diagSessionId = DIAG_SESSION_COUNTER.getAndIncrement();

	private void getNoGoodsFromGrounderAndIngest() {
		Map<Integer, NoGood> obtained = grounder.getNoGoods(assignment);
		if (Boolean.getBoolean("alpha.diagNoGoods")) {
			for (NoGood ng : obtained.values()) {
				java.util.List<String> lits = new java.util.ArrayList<>();
				for (int lit : ng) {
					lits.add(atomStore.literalToString(lit));
				}
				java.util.Collections.sort(lits);
				LOGGER.info("DIAG-NG s={} {}", diagSessionId, String.join("|", lits));
			}
		}
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
		// Capture the clean dl-0 fixpoint (minus closing atoms) at this shot's first answer set, before any
		// enumeration nogood is added, so resetForNewShot can hot-start the next shot from it regardless of
		// how many answer sets the caller pulls — a single findFirst() still leaves a usable snapshot.
		if (dl0Snapshot == null) {
			dl0Snapshot = assignment.captureDl0NonClosingSnapshot();
			// First answer set of this shot: if warm VSIDS carried it through without a cold restart, fold this
			// shot's decision count into the baseline that calibrates the adaptive cold-restart budget.
			if (ADAPTIVE_VSIDS_RESET && !cooledThisShot) {
				long used = choiceManager.getChoices() - decisionsAtShotStart;
				warmBaselineDecisions = warmBaselineDecisions == 0 ? used : (warmBaselineDecisions * 3 + used) / 4;
				cleanShotsCompleted++;
			}
		}
		if (Boolean.getBoolean("alpha.diagShotState")) {
			LOGGER.info("DIAG shot#{} SOLVED: choicePoints={} naryNG={} decisionsThisShot={}", diagShotCounter,
					choiceManager.getNumberOfChoicePoints(), store.getNoGoodCounter().getNumberOfNAryNoGoods(),
					choiceManager.getChoices() - decisionsAtShotStart);
		}
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

		if (Boolean.getBoolean("alpha.diagChoices")) {
			diagConflictCount++;
			int dlBefore = assignment.getDecisionLevel();
			if (analysisResult.backjumpLevel > diagMaxBackjumpTarget) {
				diagMaxBackjumpTarget = analysisResult.backjumpLevel;
			}
			diagBackjumpTargetSum += analysisResult.backjumpLevel;
			diagDlBeforeSum += dlBefore;
			if (diagConflictCount <= 40) {
				LOGGER.info("DIAG-CONFLICT shot#{} #{} dlBefore={} backjumpTo={} lbd~{}", diagShotCounter,
						diagConflictCount, dlBefore, analysisResult.backjumpLevel, analysisResult.learnedNoGood.size());
			} else if (diagConflictCount % 20000 == 0) {
				LOGGER.info("DIAG-CONFLICT-SUMMARY shot#{} conflicts={} avgDlBefore={} avgBackjumpTo={} maxBackjumpTo={}",
						diagShotCounter, diagConflictCount, diagDlBeforeSum / diagConflictCount,
						diagBackjumpTargetSum / diagConflictCount, diagMaxBackjumpTarget);
			}
		}
		choiceManager.backjump(analysisResult.backjumpLevel);
		NoGood learnedNoGood = analysisResult.learnedNoGood;
		if (analysisResult.enumerationDerived) {
			// Conflict analysis resolved through an enumeration nogood, so this resolvent is sound only for
			// the answer-set-blocked program. Register it as enumeration-scoped so it is purged with N_e at
			// the shot boundary instead of persisting unsoundly in the learned-nogood store across shots.
			learnedNoGood = learnedNoGood.asEnumeration();
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

		// DIAG (alpha.diagFixpointSize): report the size of the dl-0 fixpoint T at the moment the FIRST
		// unfoundedness nogood of this shot is about to be created — i.e. how much of T is pure structural
		// propagation before any foundedness reasoning contributes to it.
		if (!justificationLearnedThisShot && Boolean.getBoolean("alpha.diagFixpointSize")) {
			int dl0 = 0, dl0nonFalse = 0, totalAssigned = 0;
			int maxId = atomStore.getMaxAtomId();
			for (int a = 1; a <= maxId; a++) {
				ThriceTruth t = assignment.getTruth(a);
				if (t == null) {
					continue;
				}
				totalAssigned++;
				if (assignment.getWeakDecisionLevel(a) == 0) {
					dl0++;
					if (t != ThriceTruth.FALSE) {
						dl0nonFalse++;
					}
				}
			}
			LOGGER.info("DIAG-FIXPOINT shot#{}: |T| (dl-0 assigned) = {}  ({} non-false / {} false), totalAssigned={}, maxAtomId={}, currentDL={}",
					diagShotCounter, dl0, dl0nonFalse, dl0 - dl0nonFalse, totalAssigned, maxId, assignment.getDecisionLevel());
		}
		// USER PROPOSAL: capture the dl-0 fixpoint T *before* the first foundedness nogood of this shot is
		// added. At this instant T is purely structural/classical (no foundedness nogood exists yet), hence
		// foundedness-free transitively — so it is program-monotone and can be carried into the next shot.
		// (For a shot that never learns a foundedness nogood, provideAnswerSet captures at the first answer set
		// as before.) The first foundedness nogood always precedes the first answer set, so dl0Snapshot is null
		// here on the first one.
		// This foundedness nogood is valid only for the current program; flag the shot so the session does the
		// conservative reset next shot rather than letting it (or its dl-0 effect) persist unsoundly.
		justificationLearnedThisShot = true;
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
		// DESIGN (B) EXPERIMENT: tag foundedness (justification) nogoods as ENUMERATION so they — and, via the
		// existing enumeration taint (Antecedent.fromEnumeration -> ConflictAnalysisResult.enumerationDerived ->
		// asEnumeration), every learned nogood that resolves through one — are dropped by purgeEnumerationNoGoods
		// at the contaminated shot boundary, while ordinary LEARNT nogoods are KEPT. This tests whether we can
		// preserve the sound learned nogoods (drop only the foundedness-tainted subset) instead of dropping N_l
		// wholesale. In batch (single shot) nothing is ever purged, so the tag only affects lifecycle.
		return NoGood.enumeration(reasons);
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
			// Foundedness nogood — see justifyMbtAndBacktrack: flag the shot for the conservative reset.
			justificationLearnedThisShot = true;
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

		if (Boolean.getBoolean("alpha.diagChoices") && diagChoiceCount < 40) {
			diagChoiceCount++;
			LOGGER.info("DIAG-CHOICE shot#{} #{} dl={} lit={}", diagShotCounter, diagChoiceCount,
					assignment.getDecisionLevel(), atomStore.literalToString(literal));
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
