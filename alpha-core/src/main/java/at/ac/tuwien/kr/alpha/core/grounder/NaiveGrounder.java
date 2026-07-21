/**
 * Copyright (c) 2016-2019, the Alpha Team.
 * All rights reserved.
 * <p>
 * Additional changes made by Siemens.
 * <p>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * <p>
 * 1) Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * <p>
 * 2) Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * <p>
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
package at.ac.tuwien.kr.alpha.core.grounder;

import static at.ac.tuwien.kr.alpha.commons.util.Util.oops;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomOf;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomToLiteral;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.negateLiteral;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.api.config.GrounderHeuristicsConfiguration;
import at.ac.tuwien.kr.alpha.api.grounder.Substitution;
import at.ac.tuwien.kr.alpha.api.programs.Predicate;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;
import at.ac.tuwien.kr.alpha.api.programs.literals.Literal;
import at.ac.tuwien.kr.alpha.api.programs.terms.VariableTerm;
import at.ac.tuwien.kr.alpha.commons.AnswerSets;
import at.ac.tuwien.kr.alpha.commons.programs.atoms.Atoms;
import at.ac.tuwien.kr.alpha.commons.substitutions.BasicSubstitution;
import at.ac.tuwien.kr.alpha.commons.substitutions.Instance;
import at.ac.tuwien.kr.alpha.commons.util.Util;
import at.ac.tuwien.kr.alpha.core.common.Assignment;
import at.ac.tuwien.kr.alpha.core.common.AtomStore;
import at.ac.tuwien.kr.alpha.core.common.IntIterator;
import at.ac.tuwien.kr.alpha.core.common.NoGood;
import at.ac.tuwien.kr.alpha.core.common.NoGoodInterface;
import at.ac.tuwien.kr.alpha.core.grounder.bridges.Bridge;
import at.ac.tuwien.kr.alpha.core.grounder.instantiation.AssignmentStatus;
import at.ac.tuwien.kr.alpha.core.grounder.instantiation.BindingResult;
import at.ac.tuwien.kr.alpha.core.grounder.instantiation.DefaultLazyGroundingInstantiationStrategy;
import at.ac.tuwien.kr.alpha.core.grounder.instantiation.LiteralInstantiationResult;
import at.ac.tuwien.kr.alpha.core.grounder.instantiation.LiteralInstantiator;
import at.ac.tuwien.kr.alpha.core.grounder.structure.AnalyzeUnjustified;
import at.ac.tuwien.kr.alpha.core.programs.CompiledProgram;
import at.ac.tuwien.kr.alpha.core.programs.atoms.ChoiceAtom;
import at.ac.tuwien.kr.alpha.core.programs.atoms.RuleAtom;
import at.ac.tuwien.kr.alpha.core.programs.rules.CompiledRule;

/**
 * A semi-naive grounder.
 *
 * Copyright (c) 2016-2020, the Alpha Team.
 */
public class NaiveGrounder extends BridgedGrounder implements ProgramAnalyzingGrounder {
	private static final Logger LOGGER = LoggerFactory.getLogger(NaiveGrounder.class);

	private final WorkingMemory workingMemory = new WorkingMemory();
	private final AtomStore atomStore;
	private final NogoodRegistry registry = new NogoodRegistry();
	final NoGoodGenerator noGoodGenerator;
	private final ChoiceRecorder choiceRecorder;
	private final CompiledProgram program;
	private final AnalyzeUnjustified analyzeUnjustified;

	private final Map<Predicate, LinkedHashSet<Instance>> factsFromProgram;
	private final Map<IndexedInstanceStorage, ArrayList<FirstBindingAtom>> rulesUsingPredicateWorkingMemory = new HashMap<>();
	private final Map<Integer, CompiledRule> knownNonGroundRules;

	private ArrayList<CompiledRule> fixedRules = new ArrayList<>();
	private final ArrayList<CompiledRule> pendingFixedRules = new ArrayList<>();
	private LinkedHashSet<Atom> removeAfterObtainingNewNoGoods = new LinkedHashSet<>();
	private final boolean debugInternalChecks;

	/**
	 * When {@code true}, this grounder runs in <em>session</em> mode: facts are materialized as
	 * regular atoms in the {@link AtomStore} and forced TRUE via unit nogoods, rather than elided
	 * from generated nogoods. This makes structural nogoods valid for any subset of seen facts and is
	 * what enables fact retraction without re-grounding. See {@link NoGoodGenerator#keepFactsAsLiterals}.
	 */
	private final boolean sessionMode;

	/**
	 * Unit nogoods queued for the next {@link #getNoGoods(Assignment)} call. In session mode, every
	 * fact added via {@link #extendWithFacts(Iterable)} produces a unit nogood that gets drained here
	 * so the solver sees the new fact's truth assignment.
	 */
	private final ArrayList<NoGood> pendingFactUnitNoGoods = new ArrayList<>();

	/**
	 * Session mode only: set whenever the accumulated program changes ({@link #extendWithRules},
	 * {@link #extendWithFacts}, {@link #retractFacts}) so that the next {@link #getNoGoods(Assignment)} recomputes
	 * the unique-head set from the current program and refreshes the {@link NoGoodGenerator}. This keeps the
	 * TRANSIENT-tagged support nogoods sound: a head that gained a fact or a second defining rule no longer
	 * receives one. The program is fixed within a shot, so one recompute per change is enough.
	 */
	private boolean uniqueHeadSetDirty = false;

	/**
	 * In session mode, maps each currently-active fact atom's id to the id of its unit nogood. Used by
	 * the {@link at.ac.tuwien.kr.alpha.api.impl.SessionGrounder} on retraction: a fact's unit nogood
	 * must be excluded from replay when its fact has been retracted. Populated on bootstrap and on
	 * {@link #extendWithFacts(Iterable)}, drained on {@link #retractFacts(Iterable)}.
	 */
	private final Map<Integer, Integer> factAtomToUnitNoGoodId = new LinkedHashMap<>();

	private final GrounderHeuristicsConfiguration heuristicsConfiguration;

	// Handles instantiation of literals, i.e. supplies ground substitutions for literals of non-ground rules
	// according to the rules set by the LiteralInstantiationStrategy used by this grounder.
	private final LiteralInstantiator ruleInstantiator;
	private final DefaultLazyGroundingInstantiationStrategy instantiationStrategy;

	public NaiveGrounder(CompiledProgram program, AtomStore atomStore, boolean debugInternalChecks, Bridge... bridges) {
		this(program, atomStore, new GrounderHeuristicsConfiguration(), debugInternalChecks, bridges);
	}

	private NaiveGrounder(CompiledProgram program, AtomStore atomStore, GrounderHeuristicsConfiguration heuristicsConfiguration, boolean debugInternalChecks,
			Bridge... bridges) {
		this(program, atomStore, p -> true, heuristicsConfiguration, debugInternalChecks, false, bridges);
	}

	NaiveGrounder(CompiledProgram program, AtomStore atomStore, java.util.function.Predicate<Predicate> filter,
			GrounderHeuristicsConfiguration heuristicsConfiguration, boolean debugInternalChecks, Bridge... bridges) {
		this(program, atomStore, filter, heuristicsConfiguration, debugInternalChecks, false, bridges);
	}

	public NaiveGrounder(CompiledProgram program, AtomStore atomStore, java.util.function.Predicate<Predicate> filter,
			GrounderHeuristicsConfiguration heuristicsConfiguration, boolean debugInternalChecks, boolean sessionMode, Bridge... bridges) {
		super(filter, bridges);
		this.atomStore = atomStore;
		this.heuristicsConfiguration = heuristicsConfiguration;
		this.sessionMode = sessionMode;
		LOGGER.debug("Grounder configuration: {} (sessionMode={})", heuristicsConfiguration, sessionMode);

		this.program = program;

		// Defensive mutable copies so the grounder can be extended incrementally
		// via {@link #extendWithFacts}/{@link #extendWithRules}. All collaborators
		// (noGoodGenerator, instantiationStrategy, analyzeUnjustified) hold the
		// same reference and so will see updates.
		this.factsFromProgram = new LinkedHashMap<>();
		for (Map.Entry<Predicate, LinkedHashSet<Instance>> e : program.getFactsByPredicate().entrySet()) {
			this.factsFromProgram.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
		}
		this.knownNonGroundRules = new LinkedHashMap<>(program.getRulesById());

		this.analyzeUnjustified = new AnalyzeUnjustified(this.program, this.atomStore, this.factsFromProgram);

		this.initializeFactsAndRules();

		final Set<CompiledRule> uniqueGroundRulePerGroundHead = getRulesWithUniqueHead();
		choiceRecorder = new ChoiceRecorder(atomStore);
		noGoodGenerator = new NoGoodGenerator(atomStore, choiceRecorder, factsFromProgram, this.program, uniqueGroundRulePerGroundHead, sessionMode);

		this.debugInternalChecks = debugInternalChecks;

		// Initialize RuleInstantiator and instantiation strategy. Note that the instantiation strategy also
		// needs the current assignment, which is set with every call of getGroundInstantiations.
		this.instantiationStrategy = new DefaultLazyGroundingInstantiationStrategy(this.workingMemory, this.atomStore, this.factsFromProgram,
				this.heuristicsConfiguration.isAccumulatorEnabled());
		this.instantiationStrategy.setStaleWorkingMemoryEntries(this.removeAfterObtainingNewNoGoods);
		this.ruleInstantiator = new LiteralInstantiator(this.instantiationStrategy);
	}

	private void initializeFactsAndRules() {
		// Initialize all facts.
		for (Atom fact : program.getFacts()) {
			final Predicate predicate = fact.getPredicate();

			// Record predicate
			workingMemory.initialize(predicate);
		}

		// Register internal atoms.
		workingMemory.initialize(RuleAtom.PREDICATE);
		workingMemory.initialize(ChoiceAtom.OFF);
		workingMemory.initialize(ChoiceAtom.ON);

		// Initialize rules and constraints in working memory.
		for (CompiledRule nonGroundRule : program.getRulesById().values()) {
			// Create working memories for all predicates occurring in the rule.
			for (Predicate predicate : nonGroundRule.getOccurringPredicates()) {
				// FIXME: this also contains interval/builtin predicates that are not needed.
				workingMemory.initialize(predicate);
			}

			// If the rule has fixed ground instantiations, it is not registered but grounded once like facts.
			if (nonGroundRule.getGroundingInfo().hasFixedInstantiation()) {
				fixedRules.add(nonGroundRule);
				continue;
			}

			// Register each starting literal at the corresponding working memory.
			for (Literal literal : nonGroundRule.getGroundingInfo().getStartingLiterals()) {
				registerLiteralAtWorkingMemory(literal, nonGroundRule);
			}
		}
	}

	/**
	 * Incrementally extends this grounder with additional facts. New fact instances are added to the
	 * factsFromProgram bookkeeping and pushed into {@link #workingMemory}, where they are marked as
	 * recently-added so that the next call to {@link #getNoGoods(Assignment)} grounds any rules whose
	 * starting literals are bound by them.
	 *
	 * Safe to call after {@link #bootstrap} has run; intended use is from {@code AlphaSessionImpl}
	 * between {@code solve()} calls when only facts are added.
	 *
	 * @param newFacts facts to add (each fact's predicate is initialized in working memory on demand)
	 */
	public void extendWithFacts(Iterable<Atom> newFacts) {
		for (Atom fact : newFacts) {
			Predicate predicate = fact.getPredicate();
			LinkedHashSet<Instance> bucket = factsFromProgram.computeIfAbsent(predicate, k -> new LinkedHashSet<>());
			Instance instance = new Instance(fact.getTerms());
			if (bucket.add(instance)) {
				workingMemory.initialize(predicate);
				workingMemory.addInstance(predicate, true, instance);
				if (sessionMode) {
					queueFactUnitNoGood(fact);
					// A new fact for `predicate` removes any support nogood for that head (it now has an
					// extra, empty-body support); force a recompute before the next grounding.
					uniqueHeadSetDirty = true;
				}
			}
		}
	}

	/**
	 * Materialize a fact atom in the {@link AtomStore} and create the unit nogood that forces it TRUE.
	 * The unit nogood is queued for the next {@link #getNoGoods(Assignment)} call, and the mapping from
	 * the fact's atom id to its unit nogood id is recorded so that a later {@link #retractFacts}
	 * can be matched up by the {@link at.ac.tuwien.kr.alpha.api.impl.SessionGrounder} during replay.
	 *
	 * Idempotent: if the fact atom already has a unit nogood, this is a no-op.
	 */
	private void queueFactUnitNoGood(Atom factAtom) {
		int atomId = atomStore.putIfAbsent(factAtom);
		if (factAtomToUnitNoGoodId.containsKey(atomId)) {
			return;
		}
		// AlphaInc add_F (Algorithm 1 case add_F & Lemma 1 of "Boosting ASP by Incremental Lazy Grounding"):
		// each added fact f joins the structural store N_s as the unit nogood {Ff}_1, forcing Tf so that f is
		// part of every future answer set and can ground newly-applicable rules on demand in later shots.
		NoGood unit = NoGood.fact(negateLiteral(atomToLiteral(atomId)));
		int unitId = registry.register(unit);
		factAtomToUnitNoGoodId.put(atomId, unitId);
		pendingFactUnitNoGoods.add(unit);
	}

	/**
	 * @return whether this grounder runs in session mode (facts materialized + retraction supported)
	 */
	public boolean isSessionMode() {
		return sessionMode;
	}

	/**
	 * @return the map from fact-atom id to its unit nogood id, for use by
	 *         {@link at.ac.tuwien.kr.alpha.api.impl.SessionGrounder} when filtering cumulative nogoods
	 *         during retraction replay. The returned map is the live internal view — read-only.
	 */
	public Map<Integer, Integer> getFactAtomToUnitNoGoodId() {
		return java.util.Collections.unmodifiableMap(factAtomToUnitNoGoodId);
	}

	/**
	 * @return true iff {@code predicate} has no rule defining it in the compiled program — i.e., its
	 *         atoms can only ever be true via fact-assertion (a "pure fact" predicate). For such
	 *         predicates, retraction also permits dropping the structural nogoods that reference the
	 *         atom: with no defining rule and no fact, the atom can never become true again, so any
	 *         structural nogood requiring it as a body literal is dead weight in the cumulative replay.
	 *
	 * <p>For predicates that DO have defining rules, structural nogoods must be retained even after
	 * fact retraction, because a rule could still derive the atom.
	 */
	public boolean isPredicateOnlyDefinedAsFact(Predicate predicate) {
		java.util.HashSet<CompiledRule> definingRules = program.getPredicateDefiningRules().get(predicate);
		return definingRules == null || definingRules.isEmpty();
	}

	/**
	 * @return true iff the given ground atom is currently asserted as a fact (present in
	 *         {@code factsFromProgram} after applying any pending {@link #retractFacts}). Used by the
	 *         {@link at.ac.tuwien.kr.alpha.api.impl.SessionGrounder} retraction GC to distinguish
	 *         head atoms that are still alive (because the predicate is also a fact predicate and
	 *         this specific atom is still asserted) from derived heads whose only support has
	 *         been removed.
	 */
	public boolean isCurrentlyAFact(Atom atom) {
		LinkedHashSet<Instance> bucket = factsFromProgram.get(atom.getPredicate());
		if (bucket == null || bucket.isEmpty()) {
			return false;
		}
		return bucket.contains(new Instance(atom.getTerms()));
	}

	/**
	 * @return the atom store backing this grounder, exposed so the
	 *         {@link at.ac.tuwien.kr.alpha.api.impl.SessionGrounder} can resolve an atom id back to its
	 *         {@link Atom} (and thence to its {@link Predicate}) when classifying which cumulative
	 *         nogoods are safe to suppress on retraction.
	 */
	public AtomStore getAtomStore() {
		return atomStore;
	}

	/**
	 * Drop the given nogood ids from the {@link NogoodRegistry}. After this call, re-registering an
	 * equivalent NoGood (e.g., via re-grounding triggered by re-adding a previously-retracted fact)
	 * allocates a fresh id and flows through the normal new-nogood accumulation path.
	 *
	 * Exposed for the session-mode retraction path; not intended for general grounder use.
	 */
	public void forgetNoGoods(Set<Integer> ids) {
		registry.forget(ids);
	}

	/**
	 * Purge the given derived atoms from the {@link WorkingMemory}. Used by the session-mode
	 * retraction GC: when a derived atom is identified as dead (no live rule supports it anymore),
	 * its working-memory entry from earlier shots would otherwise prevent re-grounding cascades. A
	 * future {@link #updateAssignment} call for the same atom is a no-op if it's still in working
	 * memory (because {@link WorkingMemory#addInstance} early-exits on containsInstance), so rules
	 * starting on its predicate would not re-fire. Removing it here means re-derivation does re-mark
	 * it as recently-added and downstream rules cascade correctly.
	 *
	 * @param deadDerivedAtomIds atom ids to purge; non-existent atoms are silently skipped
	 */
	public void purgeWorkingMemoryEntries(Set<Integer> deadDerivedAtomIds) {
		for (int atomId : deadDerivedAtomIds) {
			Atom atom = atomStore.get(atomId);
			if (atom == null) {
				continue;
			}
			Predicate predicate = atom.getPredicate();
			if (!workingMemory.contains(predicate)) {
				continue;
			}
			IndexedInstanceStorage positive = workingMemory.get(predicate, true);
			Instance instance = new Instance(atom.getTerms());
			if (positive.containsInstance(instance)) {
				positive.markRecentlyAddedInstancesDone();
				positive.removeInstance(instance);
			}
		}
	}

	/**
	 * Retract the given facts from this grounder. Only valid in session mode. The facts are removed
	 * from {@code factsFromProgram} and from the working memory; the mapping in
	 * {@code factAtomToUnitNoGoodId} is updated so the {@link at.ac.tuwien.kr.alpha.api.impl.SessionGrounder}
	 * can drop the corresponding unit nogoods from the next replay.
	 *
	 * Structural nogoods are NOT invalidated: in session mode they include fact literals explicitly,
	 * so dropping the unit nogood is enough to flip the fact atom from forced-TRUE to free.
	 *
	 * @param retractedFacts facts to remove (those not present in factsFromProgram are silently skipped)
	 * @return the set of atom ids whose unit nogoods should be excluded from the next solver's replay
	 */
	/**
	 * Retract the given facts in session mode. Returns the ids of the <em>fact unit nogoods</em>
	 * ({@code {F f}_1}, recorded in {@link #factAtomToUnitNoGoodId}) that were dropped — NOT the atom ids.
	 * The caller drops exactly these nogoods; other unit nogoods on the same atom (e.g. a {@code {F a}_1}
	 * produced by a constraint {@code :- not a}) are structural and must survive, so the retraction only
	 * frees the atom from being <em>given</em>, not from being <em>required</em>.
	 */
	public Set<Integer> retractFacts(Iterable<Atom> retractedFacts) {
		if (!sessionMode) {
			throw new IllegalStateException("retractFacts requires session mode");
		}
		Set<Integer> droppedUnitNoGoodIds = new HashSet<>();
		for (Atom fact : retractedFacts) {
			Predicate predicate = fact.getPredicate();
			LinkedHashSet<Instance> bucket = factsFromProgram.get(predicate);
			if (bucket == null) {
				continue;
			}
			Instance instance = new Instance(fact.getTerms());
			if (!bucket.remove(instance)) {
				continue;
			}
			// Removing a fact can restore a head predicate's uniqueness (its last fact is gone), making it
			// eligible for support nogoods again; force a recompute before the next grounding.
			uniqueHeadSetDirty = true;
			// Remove from working memory's positive storage so further rule grounding sees the fact gone.
			// IndexedInstanceStorage.removeInstance throws when there are unprocessed recently-added
			// instances; in a retraction shot the recently-added queue may contain the just-added fact
			// if it was added then immediately retracted, so drain first.
			if (workingMemory.contains(predicate)) {
				IndexedInstanceStorage positive = workingMemory.get(predicate, true);
				positive.markRecentlyAddedInstancesDone();
				if (positive.containsInstance(instance)) {
					positive.removeInstance(instance);
				}
			}
			// Detach this fact's OWN unit nogood (only). Any other unit nogood on the same atom — e.g. a
			// {F a}_1 emitted by a constraint ':- not a' — is structural and must be kept, otherwise
			// retracting the fact would silently stop enforcing that constraint.
			if (atomStore.contains(fact)) {
				int atomId = atomStore.get(fact);
				Integer unitNoGoodId = factAtomToUnitNoGoodId.remove(atomId);
				if (unitNoGoodId != null) {
					droppedUnitNoGoodIds.add(unitNoGoodId);
				}
			}
		}
		return droppedUnitNoGoodIds;
	}

	/**
	 * Incrementally extends this grounder with additional non-ground rules.  For each rule, predicates
	 * occurring in it are initialized in working memory and its starting literals are registered, so
	 * that subsequent calls to {@link #getNoGoods(Assignment)} will ground new instances on the fly.
	 *
	 * Rules with fixed instantiation (i.e. ground rules including facts produced by stratified
	 * evaluation) are added to {@code factsFromProgram}/working memory directly via
	 * {@link #extendWithFacts} for the head atoms; their bodies are assumed already satisfied at
	 * this point.  Mixing fixed-instantiation rule addition into a live session is not currently
	 * supported and will throw.
	 *
	 * @param newRules rules to add
	 */
	/**
	 * Selectively re-marks previously-derived working-memory instances as recently-added so that a
	 * freshly-constructed solver re-triggers grounding for ground rules that may have <em>new</em>
	 * instantiations because of newly-added facts.
	 *
	 * <p>Algorithm: for each non-ground rule, if it has any positive body literal whose predicate is in
	 * {@code newFactPredicates}, then every <em>other</em> positive body literal predicate of that rule
	 * is a candidate for wake-up. Non-fact instances of those candidate predicates are re-marked as
	 * recently-added so the grounder iterates them on the next {@link #getNoGoods(Assignment)} call and
	 * generates the new ground rule instantiations that combine new facts with previously-derived atoms.
	 *
	 * <p>This avoids the blanket re-marking of <em>all</em> previously-derived atoms (the older
	 * {@code prepareForNewSolver} behaviour), which over-triggered grounding for derived predicates that
	 * are independent of the added fact predicates.  Specifically, derived predicates that appear only as
	 * heads of rules with no shared body with the new facts are correctly left untouched — their already-
	 * generated ground rules in the cumulative nogood set are sufficient.
	 *
	 * <p>Negative body literals are intentionally ignored: rules with negative literals are encoded into
	 * choice nogoods in Alpha, and the negated atom can be freshly created (and chosen on) by the
	 * solver without needing the underlying predicate's instances to be re-presented to the grounder.
	 *
	 * <p>If {@code newFactPredicates} is empty (e.g., a no-add re-solve), no wake-up is performed.
	 *
	 * @param newFactPredicates predicates whose fact instances were just added via
	 *                          {@link #extendWithFacts}
	 */
	public void selectiveWakeUpForNewFacts(Set<Predicate> newFactPredicates) {
		if (newFactPredicates.isEmpty()) {
			return;
		}
		Set<Predicate> predicatesToWakeUp = new HashSet<>();
		for (CompiledRule rule : knownNonGroundRules.values()) {
			boolean ruleUsesChangedPredicate = false;
			for (Literal lit : rule.getPositiveBody()) {
				if (newFactPredicates.contains(lit.getPredicate())) {
					ruleUsesChangedPredicate = true;
					break;
				}
			}
			if (!ruleUsesChangedPredicate) {
				continue;
			}
			// If every new-fact predicate occurring in this rule's body is also a starting
			// literal of the rule, new facts will retrigger the rule via their respective
			// FirstBindingAtom variants — re-marking the other body predicates' existing
			// instances would only duplicate joins (which dedup catches but the join work
			// is wasted). This is the common case for recursive Datalog rules like
			// reachable(X,Y) :- reachable(X,Z), edge(Z,Y), where both edge and reachable
			// are starting literals: adding new edges fires the edge-starting variant
			// automatically. If, however, some new-fact predicate is in the body but not a
			// starting literal (e.g., its variables need binding from another literal first),
			// we must still wake up the predicates that ARE starting so that (old-instance ×
			// new-fact) joins are found.
			Set<Predicate> startingPredicates = new HashSet<>();
			for (Literal starting : rule.getGroundingInfo().getStartingLiterals()) {
				startingPredicates.add(starting.getPredicate());
			}
			boolean allNewFactPredicatesInBodyAreStarting = true;
			for (Literal lit : rule.getPositiveBody()) {
				Predicate p = lit.getPredicate();
				if (newFactPredicates.contains(p) && !startingPredicates.contains(p)) {
					allNewFactPredicatesInBodyAreStarting = false;
					break;
				}
			}
			if (allNewFactPredicatesInBodyAreStarting) {
				continue;
			}
			for (Literal lit : rule.getPositiveBody()) {
				Predicate p = lit.getPredicate();
				if (newFactPredicates.contains(p)) {
					continue; // new facts already drive their own grounding via extendWithFacts
				}
				predicatesToWakeUp.add(p);
			}
		}
		for (Predicate pred : predicatesToWakeUp) {
			if (pred.isSolverInternal()) {
				continue;
			}
			if (!workingMemory.getKnownPredicates().contains(pred)) {
				continue;
			}
			IndexedInstanceStorage positive = workingMemory.get(pred, true);
			// Skip storages with no rule starting on them — re-marking would leave the
			// recently-added queue populated indefinitely (getNoGoods skips iteration when
			// no FirstBindingAtom is registered for the storage).
			ArrayList<FirstBindingAtom> firstBindingAtoms = rulesUsingPredicateWorkingMemory.get(positive);
			if (firstBindingAtoms == null || firstBindingAtoms.isEmpty()) {
				continue;
			}
			LinkedHashSet<Instance> factInstances = factsFromProgram.get(pred);
			List<Instance> toReMark = new ArrayList<>();
			for (Instance inst : positive.getAllInstances()) {
				if (factInstances != null && factInstances.contains(inst)) {
					continue;
				}
				toReMark.add(inst);
			}
			if (!toReMark.isEmpty()) {
				positive.reMarkAsRecentlyAdded(toReMark);
				workingMemory.markStorageModified(pred, true);
			}
		}
	}

	public void extendWithRules(Iterable<CompiledRule> newRules) {
		for (CompiledRule rule : newRules) {
			if (knownNonGroundRules.put(rule.getRuleId(), rule) != null) {
				continue; // already known, skip
			}
			// A new defining rule can make a previously unique-headed predicate non-unique (or, rarely, the
			// reverse); force a recompute of the support-nogood-eligible set before the next grounding.
			uniqueHeadSetDirty = true;
			for (Predicate predicate : rule.getOccurringPredicates()) {
				workingMemory.initialize(predicate);
			}
			if (rule.getGroundingInfo().hasFixedInstantiation()) {
				// Fixed-instantiation rule (ground rule, including ground constraints like ":- a.").
				// Queue for grounding on next getNoGoods call — same path as the bootstrap mechanism
				// uses for the initial program's fixed rules.
				pendingFixedRules.add(rule);
			} else {
				for (Literal literal : rule.getGroundingInfo().getStartingLiterals()) {
					registerLiteralAtWorkingMemory(literal, rule);
				}
			}
		}
	}

	/**
	 * After {@link #extendWithRules}, re-mark <em>all</em> existing instances of the new rules'
	 * positive body predicates as recently-added so the grounder iterates them on the next
	 * {@link #getNoGoods(Assignment)} call. Without this, a new rule registered against working
	 * memory only fires on instances added <em>after</em> registration, never on the existing ones.
	 *
	 * Unlike {@link #selectiveWakeUpForNewFacts}, this includes <em>both</em> fact and derived
	 * instances because the new rule has not seen any of them.
	 *
	 * @param newRules rules just added via {@link #extendWithRules}
	 */
	public void selectiveWakeUpForNewRules(Iterable<CompiledRule> newRules) {
		Set<Predicate> predicatesToWakeUp = new HashSet<>();
		for (CompiledRule rule : newRules) {
			if (rule.getGroundingInfo().hasFixedInstantiation()) {
				// Fixed-instantiation rules are ground by the pendingFixedRules drain in getNoGoods;
				// they don't need existing instances re-marked because they don't join over them.
				continue;
			}
			for (Literal lit : rule.getPositiveBody()) {
				predicatesToWakeUp.add(lit.getPredicate());
			}
		}
		for (Predicate pred : predicatesToWakeUp) {
			if (pred.isSolverInternal()) {
				continue;
			}
			if (!workingMemory.getKnownPredicates().contains(pred)) {
				continue;
			}
			IndexedInstanceStorage positive = workingMemory.get(pred, true);
			// Skip storages with no rule starting on them (see selectiveWakeUpForNewFacts).
			ArrayList<FirstBindingAtom> firstBindingAtoms = rulesUsingPredicateWorkingMemory.get(positive);
			if (firstBindingAtoms == null || firstBindingAtoms.isEmpty()) {
				continue;
			}
			List<Instance> toReMark = new ArrayList<>(positive.getAllInstances());
			if (!toReMark.isEmpty()) {
				positive.reMarkAsRecentlyAdded(toReMark);
				workingMemory.markStorageModified(pred, true);
			}
		}
	}

	private Set<CompiledRule> getRulesWithUniqueHead() {
		// Session mode: the unique-head support ("only-via"/completion) nogood {Tp, F(body)} is non-monotone — a
		// fact or a second defining rule for p added in a LATER shot gives p another support and falsifies it
		// (e.g. "{a}. p:-a." then adding fact "p." would lose the answer set {p}). Rather than forgo the
		// optimisation, session mode emits it tagged TRANSIENT (see NoGoodGenerator) so the between-shot purge
		// drops it and taints any learned resolvent, the dual of how foundedness nogoods are handled. Soundness
		// then only requires that the set be computed against the CURRENT accumulated program — knownNonGroundRules
		// (which grows with extendWithRules, unlike the frozen `program`) and the live factsFromProgram — so a head
		// that has since gained a fact or a second rule is correctly excluded. The grounder recomputes this after
		// every program change (see the uniqueHeadSetDirty drain in getNoGoods) and refreshes the NoGoodGenerator.
		if (sessionMode) {
			return getRulesWithUniqueHeadFromCurrentProgram();
		}
		// FIXME: below optimisation (adding support nogoods if there is only one rule instantiation per unique atom over the interpretation) could
		// be done as a transformation (adding a non-ground constraint corresponding to the nogood that is generated by the grounder).
		// Record all unique rule heads.
		final Set<CompiledRule> uniqueGroundRulePerGroundHead = new HashSet<>();

		for (Map.Entry<Predicate, LinkedHashSet<CompiledRule>> headDefiningRules : program.getPredicateDefiningRules().entrySet()) {
			if (headDefiningRules.getValue().size() != 1) {
				continue;
			}

			CompiledRule nonGroundRule = headDefiningRules.getValue().iterator().next();
			// Check that all variables of the body also occur in the head (otherwise grounding is not unique).
			Atom headAtom = nonGroundRule.getHeadAtom();

			// Rule is not guaranteed unique if there are facts for it.
			HashSet<Instance> potentialFacts = factsFromProgram.get(headAtom.getPredicate());
			if (potentialFacts != null && !potentialFacts.isEmpty()) {
				continue;
			}

			// Collect head and body variables.
			HashSet<VariableTerm> occurringVariablesHead = new HashSet<>(headAtom.toLiteral().getBindingVariables());
			HashSet<VariableTerm> occurringVariablesBody = new HashSet<>();
			for (Literal lit : nonGroundRule.getPositiveBody()) {
				occurringVariablesBody.addAll(lit.getBindingVariables());
			}
			occurringVariablesBody.removeAll(occurringVariablesHead);

			// Check if ever body variables occurs in the head.
			if (occurringVariablesBody.isEmpty()) {
				uniqueGroundRulePerGroundHead.add(nonGroundRule);
			}
		}
		return uniqueGroundRulePerGroundHead;
	}

	/**
	 * The session-mode analogue of the batch computation in {@link #getRulesWithUniqueHead()}: identical logic,
	 * but the per-head-predicate defining rules are taken from the CURRENT accumulated {@link #knownNonGroundRules}
	 * (which grows across shots via {@link #extendWithRules}) rather than the frozen construction-time
	 * {@code program.getPredicateDefiningRules()}, and facts from the live {@link #factsFromProgram}. This makes a
	 * head that has since gained a second defining rule or a fact drop out of the set, which is exactly what keeps
	 * the TRANSIENT-tagged support nogoods sound: none is ever emitted for a head that currently has another
	 * support. The program does not change within a shot, so recomputing once per program change (see the
	 * {@code uniqueHeadSetDirty} drain in {@link #getNoGoods(Assignment)}) suffices.
	 */
	private Set<CompiledRule> getRulesWithUniqueHeadFromCurrentProgram() {
		// Group the current non-ground rules by head predicate (constraints have no head; skip them).
		final Map<Predicate, LinkedHashSet<CompiledRule>> definingRulesByHeadPredicate = new LinkedHashMap<>();
		for (CompiledRule rule : knownNonGroundRules.values()) {
			if (rule.isConstraint()) {
				continue;
			}
			definingRulesByHeadPredicate.computeIfAbsent(rule.getHeadAtom().getPredicate(), k -> new LinkedHashSet<>()).add(rule);
		}

		final Set<CompiledRule> uniqueGroundRulePerGroundHead = new HashSet<>();
		for (Map.Entry<Predicate, LinkedHashSet<CompiledRule>> headDefiningRules : definingRulesByHeadPredicate.entrySet()) {
			if (headDefiningRules.getValue().size() != 1) {
				continue;
			}
			CompiledRule nonGroundRule = headDefiningRules.getValue().iterator().next();
			Atom headAtom = nonGroundRule.getHeadAtom();

			// Rule is not guaranteed unique if there are (currently) facts for the head predicate.
			LinkedHashSet<Instance> potentialFacts = factsFromProgram.get(headAtom.getPredicate());
			if (potentialFacts != null && !potentialFacts.isEmpty()) {
				continue;
			}

			// All body variables must occur in the head, otherwise the ground body is not unique to the head.
			HashSet<VariableTerm> occurringVariablesHead = new HashSet<>(headAtom.toLiteral().getBindingVariables());
			HashSet<VariableTerm> occurringVariablesBody = new HashSet<>();
			for (Literal lit : nonGroundRule.getPositiveBody()) {
				occurringVariablesBody.addAll(lit.getBindingVariables());
			}
			occurringVariablesBody.removeAll(occurringVariablesHead);
			if (occurringVariablesBody.isEmpty()) {
				uniqueGroundRulePerGroundHead.add(nonGroundRule);
			}
		}
		return uniqueGroundRulePerGroundHead;
	}

	/**
	 * Registers a starting literal of a NonGroundRule at its corresponding working memory.
	 * 
	 * @param nonGroundRule the rule in which the literal occurs.
	 */
	private void registerLiteralAtWorkingMemory(Literal literal, CompiledRule nonGroundRule) {
		if (literal.isNegated()) {
			throw new RuntimeException("Literal to register is negated. Should not happen.");
		}
		IndexedInstanceStorage workingMemory = this.workingMemory.get(literal.getPredicate(), true);
		rulesUsingPredicateWorkingMemory.putIfAbsent(workingMemory, new ArrayList<>());
		rulesUsingPredicateWorkingMemory.get(workingMemory).add(new FirstBindingAtom(nonGroundRule, literal));
	}

	@Override
	public AnswerSet assignmentToAnswerSet(Iterable<Integer> trueAtoms) {
		Map<Predicate, SortedSet<Atom>> predicateInstances = new LinkedHashMap<>();
		SortedSet<Predicate> knownPredicates = new TreeSet<>();

		// Iterate over all true atomIds, computeNextAnswerSet instances from atomStore and add them if not filtered.
		for (int trueAtom : trueAtoms) {
			final Atom atom = atomStore.get(trueAtom);
			Predicate predicate = atom.getPredicate();

			// Skip atoms over internal predicates.
			if (predicate.isInternal()) {
				continue;
			}

			// Skip filtered predicates.
			if (!filter.test(predicate)) {
				continue;
			}

			knownPredicates.add(predicate);
			predicateInstances.putIfAbsent(predicate, new TreeSet<>());
			Set<Atom> instances = predicateInstances.get(predicate);
			instances.add(atom);
		}

		// Add true atoms from facts.
		// In session mode, facts are already represented as real atoms in the assignment (via unit
		// nogoods), so they appear in trueAtoms and are picked up by the loop above — re-adding them
		// here is redundant (TreeSet dedups but the iteration is wasted work).
		if (!sessionMode) {
			for (Map.Entry<Predicate, LinkedHashSet<Instance>> facts : factsFromProgram.entrySet()) {
				Predicate factPredicate = facts.getKey();
				// Skip atoms over internal predicates.
				if (factPredicate.isInternal()) {
					continue;
				}
				// Skip filtered predicates.
				if (!filter.test(factPredicate)) {
					continue;
				}
				// Skip predicates without any instances.
				if (facts.getValue().isEmpty()) {
					continue;
				}
				knownPredicates.add(factPredicate);
				predicateInstances.putIfAbsent(factPredicate, new TreeSet<>());
				for (Instance factInstance : facts.getValue()) {
					SortedSet<Atom> instances = predicateInstances.get(factPredicate);
					instances.add(Atoms.newBasicAtom(factPredicate, factInstance.terms));
				}
			}
		}

		if (knownPredicates.isEmpty()) {
			return AnswerSets.EMPTY_SET;
		}

		return AnswerSets.newAnswerSet(knownPredicates, predicateInstances);
	}

	/**
	 * Prepares facts of the input program for joining and derives all NoGoods representing ground rules. May only be called once.
	 * 
	 * @return
	 */
	protected HashMap<Integer, NoGood> bootstrap() {
		final HashMap<Integer, NoGood> groundNogoods = new LinkedHashMap<>();

		for (Predicate predicate : factsFromProgram.keySet()) {
			// Instead of generating NoGoods, add instance to working memories directly.
			workingMemory.addInstances(predicate, true, factsFromProgram.get(predicate));
		}

		// In session mode, materialize each fact as a real atom in the AtomStore and queue its unit
		// nogood so the solver assigns it TRUE on its next propagation cycle. Done before fixed-rule
		// grounding so that fact-keeping NoGoodGenerator paths can putIfAbsent against an AtomStore
		// that already knows about the facts (avoids duplicate id allocation).
		if (sessionMode) {
			for (Map.Entry<Predicate, LinkedHashSet<Instance>> entry : factsFromProgram.entrySet()) {
				Predicate predicate = entry.getKey();
				for (Instance instance : entry.getValue()) {
					queueFactUnitNoGood(at.ac.tuwien.kr.alpha.commons.programs.atoms.Atoms.newBasicAtom(predicate, instance.terms));
				}
			}
		}

		for (CompiledRule nonGroundRule : fixedRules) {
			// Generate NoGoods for all rules that have a fixed grounding.
			RuleGroundingOrder groundingOrder = nonGroundRule.getGroundingInfo().getFixedGroundingOrder();
			BindingResult bindingResult = getGroundInstantiations(nonGroundRule, groundingOrder, new BasicSubstitution(), null);
			groundAndRegister(nonGroundRule, bindingResult.getGeneratedSubstitutions(), groundNogoods);
		}

		fixedRules = null;

		return groundNogoods;
	}

	@Override
	public Map<Integer, NoGood> getNoGoods(Assignment currentAssignment) {
		// Session mode: if the program changed since the last grounding, recompute which heads still qualify for a
		// support nogood (a head that gained a fact or a second defining rule drops out) and refresh the generator
		// before any instance is ground this shot. Done here — the single grounding entry point — so it covers
		// bootstrap, fixed rules, and join grounding uniformly. The program is fixed within a shot, so once suffices.
		if (uniqueHeadSetDirty) {
			noGoodGenerator.setUniqueGroundRulePerGroundHead(getRulesWithUniqueHead());
			uniqueHeadSetDirty = false;
		}
		// In first call, prepare facts and ground rules.
		final Map<Integer, NoGood> newNoGoods = fixedRules != null ? bootstrap() : new LinkedHashMap<>();

		// In session mode, drain any fact unit nogoods queued by bootstrap() or by extendWithFacts()
		// since the last call. They must be emitted to the solver so the new fact atoms get assigned TRUE.
		if (!pendingFactUnitNoGoods.isEmpty()) {
			for (NoGood unit : pendingFactUnitNoGoods) {
				// register() handles deduplication via NoGood equality; we just need the id.
				int id = registry.register(unit);
				newNoGoods.putIfAbsent(id, unit);
			}
			pendingFactUnitNoGoods.clear();
		}

		// Drain any pending fixed-instantiation rules added via extendWithRules since last call.
		// Like bootstrap(), pass a null assignment: fixed-instantiation rules are evaluated against
		// the program structure, not the solver's current truth assignment, so passing the
		// (typically empty) live assignment would cause the instantiation strategy to reject body
		// literals as unassigned and silently drop the rule.
		if (!pendingFixedRules.isEmpty()) {
			for (CompiledRule nonGroundRule : pendingFixedRules) {
				RuleGroundingOrder groundingOrder = nonGroundRule.getGroundingInfo().getFixedGroundingOrder();
				BindingResult bindingResult = getGroundInstantiations(nonGroundRule, groundingOrder,
						new BasicSubstitution(), null);
				groundAndRegister(nonGroundRule, bindingResult.getGeneratedSubstitutions(), newNoGoods);
			}
			pendingFixedRules.clear();
		}

		// Compute new ground rule (evaluate joins with newly changed atoms)
		for (IndexedInstanceStorage modifiedWorkingMemory : workingMemory.modified()) {
			// Skip predicates solely used in the solver which do not occur in rules.
			Predicate workingMemoryPredicate = modifiedWorkingMemory.getPredicate();
			if (workingMemoryPredicate.isSolverInternal()) {
				// Still clear recently-added so the next getNoGoods call can re-process these
				// storages cleanly. Without this, recently-added entries accumulate across
				// solves on solver-internal storages and trigger an exception later when
				// removeAfterObtainingNewNoGoods tries to remove an instance.
				modifiedWorkingMemory.markRecentlyAddedInstancesDone();
				continue;
			}

			// Iterate over all rules whose body contains the interpretation corresponding to the current workingMemory.
			final ArrayList<FirstBindingAtom> firstBindingAtoms = rulesUsingPredicateWorkingMemory.get(modifiedWorkingMemory);

			// Skip working memories that are not used by any rule.
			if (firstBindingAtoms == null) {
				modifiedWorkingMemory.markRecentlyAddedInstancesDone();
				continue;
			}

			for (FirstBindingAtom firstBindingAtom : firstBindingAtoms) {
				// Use the recently added instances from the modified working memory to construct an initial substitution
				CompiledRule nonGroundRule = firstBindingAtom.rule;

				// Generate substitutions from each recent instance.
				for (Instance instance : modifiedWorkingMemory.getRecentlyAddedInstances()) {
					// Check instance if it matches with the atom.

					final Substitution unifier = BasicSubstitution.specializeSubstitution(firstBindingAtom.startingLiteral, instance,
							BasicSubstitution.EMPTY_SUBSTITUTION);

					if (unifier == null) {
						continue;
					}

					final BindingResult bindingResult = getGroundInstantiations(
							nonGroundRule,
							nonGroundRule.getGroundingInfo().orderStartingFrom(firstBindingAtom.startingLiteral),
							unifier,
							currentAssignment);

					groundAndRegister(nonGroundRule, bindingResult.getGeneratedSubstitutions(), newNoGoods);
				}
			}

			// Mark instances added by updateAssignment as done
			modifiedWorkingMemory.markRecentlyAddedInstancesDone();
		}

		workingMemory.reset();
		for (Atom removeAtom : removeAfterObtainingNewNoGoods) {
			final IndexedInstanceStorage storage = workingMemory.get(removeAtom, true);
			Instance instance = new Instance(removeAtom.getTerms());
			if (storage.containsInstance(instance)) {
				// permissive grounder heuristics may attempt to remove instances that are not yet in the working memory
				storage.removeInstance(instance);
			}
		}

		// Re-Initialize the stale working memory entries set and pass to instantiation strategy.
		removeAfterObtainingNewNoGoods = new LinkedHashSet<>();
		instantiationStrategy.setStaleWorkingMemoryEntries(removeAfterObtainingNewNoGoods);
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Grounded NoGoods are:");
			for (Map.Entry<Integer, NoGood> noGoodEntry : newNoGoods.entrySet()) {
				LOGGER.debug("{} == {}", noGoodEntry.getValue(), atomStore.noGoodToString(noGoodEntry.getValue()));
			}
			LOGGER.debug("{}", choiceRecorder);
		}

		if (debugInternalChecks) {
			checkTypesOfNoGoods(newNoGoods.values());
		}

		return newNoGoods;
	}

	/**
	 * Grounds the given {@code nonGroundRule} by applying the given {@code substitutions} and registers the nogoods generated during that
	 * process.
	 *
	 * @param nonGroundRule the rule to be grounded.
	 * @param substitutions the substitutions to be applied.
	 * @param newNoGoods    a set of nogoods to which newly generated nogoods will be added.
	 */
	private void groundAndRegister(final CompiledRule nonGroundRule, final List<Substitution> substitutions, final Map<Integer, NoGood> newNoGoods) {
		for (Substitution substitution : substitutions) {
			List<NoGood> generatedNoGoods = noGoodGenerator.generateNoGoodsFromGroundSubstitution(nonGroundRule, substitution);
			registry.register(generatedNoGoods, newNoGoods);
		}
	}

	@Override
	public int register(NoGood noGood) {
		return registry.register(noGood);
	}

	// Ideally, this method should be private. It's only visible because NaiveGrounderTest needs to access it.
	BindingResult getGroundInstantiations(CompiledRule rule, RuleGroundingOrder groundingOrder, Substitution partialSubstitution,
			Assignment currentAssignment) {
		int tolerance = heuristicsConfiguration.getTolerance(rule.isConstraint());
		if (tolerance < 0) {
			tolerance = Integer.MAX_VALUE;
		}

		// Update instantiationStrategy with current assignment.
		// Note: Actually the assignment could be an instance variable of the grounder (shared with solver),
		// but this would have a larger impact on grounder/solver communication design as a whole.
		instantiationStrategy.setCurrentAssignment(currentAssignment);
		BindingResult bindingResult = bindNextAtomInRule(groundingOrder, 0, tolerance, tolerance, partialSubstitution);
		if (LOGGER.isDebugEnabled()) {
			for (int i = 0; i < bindingResult.size(); i++) {
				Integer numberOfUnassignedPositiveBodyAtoms = bindingResult.getNumbersOfUnassignedPositiveBodyAtoms().get(i);
				if (numberOfUnassignedPositiveBodyAtoms > 0) {
					LOGGER.debug("Grounded rule in which {} positive atoms are still unassigned: {} (substitution: {})", numberOfUnassignedPositiveBodyAtoms,
							rule, bindingResult.getGeneratedSubstitutions().get(i));
				}
			}
		}
		return bindingResult;
	}

	/**
	 * Helper method used by {@link NaiveGrounder#bindNextAtomInRule(RuleGroundingOrderImpl, int, int, int, BasicSubstitution)}.
	 *
	 * Takes an <code>ImmutablePair</code> of a {@link BasicSubstitution} and an accompanying {@link AssignmentStatus} and calls
	 * <code>bindNextAtomInRule</code> for the next literal in the grounding order.
	 * If the assignment status for the last bound literal was {@link AssignmentStatus#UNASSIGNED}, the <code>remainingTolerance</code>
	 * parameter is decreased by 1. If the remaining tolerance drops below zero, this method returns an empty {@link BindingResult}.
	 *
	 * @param groundingOrder
	 * @param orderPosition
	 * @param originalTolerance
	 * @param remainingTolerance
	 * @param lastLiteralBindingResult
	 * @return the result of calling bindNextAtomInRule on the next literal in the grounding order, or an empty binding result if remaining
	 *         tolerance is less than zero.
	 */
	private BindingResult continueBinding(RuleGroundingOrder groundingOrder, int orderPosition, int originalTolerance, int remainingTolerance,
			ImmutablePair<Substitution, AssignmentStatus> lastLiteralBindingResult) {
		Substitution substitution = lastLiteralBindingResult.left;
		AssignmentStatus lastBoundLiteralAssignmentStatus = lastLiteralBindingResult.right;
		switch (lastBoundLiteralAssignmentStatus) {
			case TRUE:
				return advanceAndBindNextAtomInRule(groundingOrder, orderPosition, originalTolerance, remainingTolerance, substitution);
			case UNASSIGNED:
				// The last literal bound to obtain the current substitution has not been assigned a truth value by the solver yet.
				// If we still have enough tolerance, we can continue grounding nevertheless.
				int toleranceForNextRun = remainingTolerance - 1;
				if (toleranceForNextRun >= 0) {
					return advanceAndBindNextAtomInRule(groundingOrder, orderPosition, originalTolerance, toleranceForNextRun, substitution);
				} else {
					return BindingResult.empty();
				}
			case FALSE:
				throw Util.oops("Got an assignmentStatus FALSE for literal " + groundingOrder.getLiteralAtOrderPosition(orderPosition) + " and substitution "
						+ substitution + " - should not happen!");
			default:
				throw Util.oops("Got unsupported assignmentStatus " + lastBoundLiteralAssignmentStatus);
		}
	}

	private BindingResult advanceAndBindNextAtomInRule(RuleGroundingOrder groundingOrder, int orderPosition, int originalTolerance, int remainingTolerance,
			Substitution partialSubstitution) {
		groundingOrder.considerUntilCurrentEnd();
		return bindNextAtomInRule(groundingOrder, orderPosition + 1, originalTolerance, remainingTolerance, partialSubstitution);
	}

	private BindingResult pushBackAndBindNextAtomInRule(RuleGroundingOrder groundingOrder, int orderPosition, int originalTolerance, int remainingTolerance,
			Substitution partialSubstitution) {
		RuleGroundingOrder modifiedGroundingOrder = groundingOrder.pushBack(orderPosition);
		if (modifiedGroundingOrder == null) {
			return BindingResult.empty();
		}
		return bindNextAtomInRule(modifiedGroundingOrder, orderPosition + 1, originalTolerance, remainingTolerance, partialSubstitution);
	}

	//@formatter:off
	/**
	 * Computes ground substitutions for a literal based on a {@link RuleGroundingOrderImpl} and a {@link BasicSubstitution}.
	 *
	 * Computes ground substitutions for the literal at position <code>orderPosition</code> of <code>groundingOrder</code>
	 * Actual substitutions are computed by this grounder's {@link LiteralInstantiator}. 
	 *
	 * @param groundingOrder a {@link RuleGroundingOrderImpl} representing the body literals of a rule in the 
	 * 						 sequence in which the should be bound during grounding.
	 * @param orderPosition the current position within <code>groundingOrder</code>, indicates which literal should be bound
	 * @param originalTolerance the original tolerance of the used grounding heuristic
	 * @param remainingTolerance the remaining tolerance, determining if binding continues in the presence of substitutions based on unassigned atoms
	 * @param partialSubstitution a substitution
	 * @return a {@link BindingResult} representing applicable ground substitutions for all literals after orderPosition in groundingOrder
	 */
	//@formatter:on
	private BindingResult bindNextAtomInRule(RuleGroundingOrder groundingOrder, int orderPosition, int originalTolerance, int remainingTolerance,
			Substitution partialSubstitution) {
		Literal currentLiteral = groundingOrder.getLiteralAtOrderPosition(orderPosition);
		if (currentLiteral == null) {
			LOGGER.trace("No more literals found in grounding order, therefore stopping binding!");
			return BindingResult.singleton(partialSubstitution, originalTolerance - remainingTolerance);
		}
		LOGGER.trace("Binding current literal {} with remaining tolerance {} and partial substitution {}.", currentLiteral,
				remainingTolerance, partialSubstitution);
		LiteralInstantiationResult instantiationResult = ruleInstantiator.instantiateLiteral(currentLiteral, partialSubstitution);
		switch (instantiationResult.getType()) {
			case CONTINUE:
				/*
				 * Recursively call bindNextAtomInRule for each generated substitution
				 * and the next literal in the grounding order (i.e. advance), thereby reducing remaining
				 * tolerance by 1 iff a substitution uses an unassigned ground atom.
				 * If remainingTolerance falls below zero, an empty {@link BindingResult} is returned.
				 */
				List<ImmutablePair<Substitution, AssignmentStatus>> substitutionInfos = instantiationResult.getSubstitutions();
				LOGGER.trace("Literal instantiator yielded {} substitutions for literal {}.", substitutionInfos.size(), currentLiteral);
				BindingResult retVal = new BindingResult();
				for (ImmutablePair<Substitution, AssignmentStatus> substitutionInfo : substitutionInfos) {
					retVal.add(this.continueBinding(groundingOrder, orderPosition, originalTolerance, remainingTolerance,
							substitutionInfo));
				}
				return retVal;
			case PUSH_BACK:
				/*
				 * Delegate to pushBackAndBindNextAtomInRule(RuleGroundingOrder, int, int, int, Substitution, Assignment).
				 * Pushes the current literal to the end of the grounding order and calls bindNextAtomInRule with the modified grounding oder.
				 */
				LOGGER.trace("Pushing back literal {} in grounding order.", currentLiteral);
				return pushBackAndBindNextAtomInRule(groundingOrder, orderPosition, originalTolerance, remainingTolerance, partialSubstitution);
			case MAYBE_PUSH_BACK:
				/*
				 * Indicates that the rule instantiator could not find any substitutions for the current literal. If a permissive grounder heuristic is in
				 * use, push the current literal to the end of the grounding order and proceed with the next one, otherwise return an empty BindingResult.
				 */
				if (originalTolerance > 0) {
					LOGGER.trace(
							"No substitutions yielded by literal instantiator for literal {}, but using permissive heuristic, therefore pushing the literal back.",
							currentLiteral);
					// This occurs when the grounder heuristic in use is a "permissive" one,
					// i.e. it is deemed acceptable to have ground rules where a number of body atoms are not yet assigned a truth value by the solver.
					return pushBackAndBindNextAtomInRule(groundingOrder, orderPosition, originalTolerance, remainingTolerance, partialSubstitution);
				} else {
					LOGGER.trace("No substitutions found for literal {}", currentLiteral);
					return BindingResult.empty();
				}
			case STOP_BINDING:
				LOGGER.trace("No substitutions found for literal {}", currentLiteral);
				return BindingResult.empty();
			default:
				throw Util.oops("Unhandled literal instantiation result type: " + instantiationResult.getType());
		}
	}

	@Override
	public Pair<Map<Integer, Integer>, Map<Integer, Integer>> getChoiceAtoms() {
		return choiceRecorder.getAndResetChoices();
	}

	@Override
	public Map<Integer, Set<Integer>> getHeadsToBodies() {
		return choiceRecorder.getAndResetHeadsToBodies();
	}

	@Override
	public void updateAssignment(IntIterator it) {
		while (it.hasNext()) {
			workingMemory.addInstance(atomStore.get(it.next()), true);
		}
	}

	@Override
	public void forgetAssignment(int[] atomIds) {
		throw new UnsupportedOperationException("Forgetting assignments is not implemented");
	}

	@Override
	public CompiledRule getNonGroundRule(Integer ruleId) {
		return knownNonGroundRules.get(ruleId);
	}

	@Override
	public boolean isFact(Atom atom) {
		LinkedHashSet<Instance> instances = factsFromProgram.get(atom.getPredicate());
		if (instances == null) {
			return false;
		}
		return instances.contains(new Instance(atom.getTerms()));
	}

	@Override
	public Set<Literal> justifyAtom(int atomToJustify, Assignment currentAssignment) {
		Set<Literal> literals = analyzeUnjustified.analyze(atomToJustify, currentAssignment);
		// Remove facts from justification before handing it over to the solver.
		for (Iterator<Literal> iterator = literals.iterator(); iterator.hasNext();) {
			Literal literal = iterator.next();
			if (literal.isNegated()) {
				continue;
			}
			LinkedHashSet<Instance> factsOverPredicate = factsFromProgram.get(literal.getPredicate());
			if (factsOverPredicate != null && factsOverPredicate.contains(new Instance(literal.getAtom().getTerms()))) {
				iterator.remove();
			}
		}
		return literals;
	}

	/**
	 * Checks that every nogood not marked as {@link NoGoodInterface.Type#INTERNAL} contains only
	 * atoms which are not {@link PredicateImpl#isSolverInternal()} (except {@link RuleAtom}s, which are allowed).
	 *
	 * @param newNoGoods
	 */
	private void checkTypesOfNoGoods(Collection<NoGood> newNoGoods) {
		for (NoGood noGood : newNoGoods) {
			if (noGood.getType() != NoGoodInterface.Type.INTERNAL) {
				for (int literal : noGood) {
					Atom atom = atomStore.get(atomOf(literal));
					if (atom.getPredicate().isSolverInternal() && !(atom instanceof RuleAtom)) {
						throw oops("NoGood containing atom of internal predicate " + atom + " is " + noGood.getType() + " instead of INTERNAL");
					}
				}
			}
		}
	}

	private static class FirstBindingAtom {
		final CompiledRule rule;
		final Literal startingLiteral;

		FirstBindingAtom(CompiledRule rule, Literal startingLiteral) {
			this.rule = rule;
			this.startingLiteral = startingLiteral;
		}
	}

}
