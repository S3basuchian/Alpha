package at.ac.tuwien.kr.alpha.api.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import at.ac.tuwien.kr.alpha.api.AnswerSet;
import at.ac.tuwien.kr.alpha.core.common.Assignment;
import at.ac.tuwien.kr.alpha.core.common.IntIterator;
import java.util.List;

import at.ac.tuwien.kr.alpha.core.common.NoGood;
import at.ac.tuwien.kr.alpha.core.grounder.Grounder;
import at.ac.tuwien.kr.alpha.core.grounder.NaiveGrounder;

/**
 * Recording/replaying decorator around {@link NaiveGrounder} that lets a session reuse one grounder across
 * many fresh {@code DefaultSolver} instances.
 *
 * The grounder/solver protocol is delta-based: {@code getNoGoods}, {@code getChoiceAtoms}, and
 * {@code getHeadsToBodies} each return only what is new since the last call.  But the call order is NOT
 * tight: the solver typically calls {@code getNoGoods} multiple times (within propagation cycles) before
 * the next {@code getChoiceAtoms} / {@code getHeadsToBodies} from {@code choose()}.  This wrapper:
 *
 * <ul>
 *   <li>Drains all three from the delegate on every {@code getNoGoods} call (because each is reset on
 *       read by the underlying recorder), accumulating into per-call <em>pending</em> queues that the
 *       solver consumes on its next {@code getChoiceAtoms} / {@code getHeadsToBodies}.</li>
 *   <li>Also accumulates into a <em>cumulative</em> view that persists across the lifetime of this
 *       wrapper.</li>
 *   <li>When {@link #armForReplay} is called (by the session, before constructing a fresh solver), the
 *       next {@code getNoGoods} returns the cumulative nogoods and seeds the pending queues with the
 *       cumulative choice / heads-to-bodies state, so the fresh solver sees everything it needs.</li>
 * </ul>
 */
final class SessionGrounder implements Grounder {

	private final NaiveGrounder delegate;

	private final Map<Integer, NoGood> cumulativeNoGoods = new LinkedHashMap<>();
	private final Map<Integer, Integer> cumulativeChoiceOn = new LinkedHashMap<>();
	private final Map<Integer, Integer> cumulativeChoiceOff = new LinkedHashMap<>();
	private final Map<Integer, Set<Integer>> cumulativeHeadsToBodies = new LinkedHashMap<>();

	// Pending state the solver hasn't yet consumed via getChoiceAtoms / getHeadsToBodies.
	private final Map<Integer, Integer> pendingChoiceOn = new LinkedHashMap<>();
	private final Map<Integer, Integer> pendingChoiceOff = new LinkedHashMap<>();
	private final Map<Integer, Set<Integer>> pendingHeadsToBodies = new LinkedHashMap<>();

	private boolean replayOnNextBatch = false;

	SessionGrounder(NaiveGrounder delegate) {
		this.delegate = delegate;
	}

	/**
	 * Arm the wrapper so that the next {@code getNoGoods} call returns the entire cumulative state to
	 * the next (presumably fresh) solver instead of the delegate's current delta.
	 */
	void armForReplay() {
		this.replayOnNextBatch = true;
	}

	/**
	 * Prune the cumulative replay state for a retraction: drop the retracted facts' unit nogoods
	 * {@code {F f}_1}, and keep everything else. The structural nogoods of the grounded rules stay in the
	 * cumulative recording (and in the AtomStore); a dead one — whose body atom can no longer become true
	 * — is simply inert once that atom is closed to false, so it does no harm, and re-adding the fact
	 * re-activates it with no re-grounding. Consequently there is <em>no</em> dead-atom derivability scan,
	 * no β-atom removal, and no working-memory purge.
	 *
	 * <p>The session rebuilds the solver from this (near-unchanged) cumulative state on the next solve, so
	 * the rebuilt solver installs correct two-watched-literal watches from scratch. That is what makes
	 * retraction sound: keeping the live solver instead would carry watches placed for the previous shot's
	 * (now-cleared) assignment, which mis-propagate multi-ary nogoods and yield spurious UNSAT.
	 *
	 * @param retractedAtomIds atom ids of facts that were retracted this shot
	 */
	void gcRetractedState(Set<Integer> retractedAtomIds) {
		if (retractedAtomIds.isEmpty()) {
			return;
		}

		// Drop only the retracted facts' unit nogoods {F f}_1 from the cumulative recording; keep every
		// other (structural) nogood. A dead structural nogood (whose body atom can no longer be true) is
		// inert once that atom is closed to false, and re-adding the fact cheaply re-activates it without
		// re-grounding — so no dead-atom scan, no atom removal, and no working-memory purge are needed. The
		// live solver is discarded and rebuilt from this (near-unchanged) cumulative state on the next
		// solve, which re-installs correct watches — that is what keeps retraction sound (a kept live
		// solver would carry stale watches for the previous shot's assignment).
		Set<Integer> unitIdsToRemove = new java.util.HashSet<>();
		for (Map.Entry<Integer, NoGood> e : cumulativeNoGoods.entrySet()) {
			NoGood n = e.getValue();
			if (n.size() == 1) {
				int atomId = at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomOf(n.getLiteral(0));
				if (retractedAtomIds.contains(atomId)) {
					unitIdsToRemove.add(e.getKey());
				}
			}
		}
		cumulativeNoGoods.keySet().removeAll(unitIdsToRemove);
		delegate.forgetNoGoods(unitIdsToRemove);
	}

	/**
	 * The surviving unit (size-1) nogoods in the cumulative recording — the facts (and structural units)
	 * that must be re-forced at decision level 0 after an in-place retraction clears the trail. Reflects
	 * the state after {@link #gcRetractedState} has dropped the retracted facts' units.
	 */
	List<NoGood> survivingUnitNoGoods() {
		List<NoGood> units = new java.util.ArrayList<>();
		for (NoGood n : cumulativeNoGoods.values()) {
			if (n.size() == 1) {
				units.add(n);
			}
		}
		return units;
	}

	@Override
	public Map<Integer, NoGood> getNoGoods(Assignment assignment) {
		// Drain all three from the delegate — each is reset-on-read, so we must capture them now.
		Map<Integer, NoGood> freshNoGoods = delegate.getNoGoods(assignment);
		Pair<Map<Integer, Integer>, Map<Integer, Integer>> freshChoices = delegate.getChoiceAtoms();
		Map<Integer, Set<Integer>> freshHeadsToBodies = delegate.getHeadsToBodies();

		// Always accumulate into cumulative.
		cumulativeNoGoods.putAll(freshNoGoods);
		cumulativeChoiceOn.putAll(freshChoices.getLeft());
		cumulativeChoiceOff.putAll(freshChoices.getRight());
		mergeHeadsToBodies(cumulativeHeadsToBodies, freshHeadsToBodies);

		// Always queue into pending so the solver gets them on its next getChoiceAtoms/getHeadsToBodies.
		pendingChoiceOn.putAll(freshChoices.getLeft());
		pendingChoiceOff.putAll(freshChoices.getRight());
		mergeHeadsToBodies(pendingHeadsToBodies, freshHeadsToBodies);

		if (replayOnNextBatch) {
			replayOnNextBatch = false;
			pendingChoiceOn.putAll(cumulativeChoiceOn);
			pendingChoiceOff.putAll(cumulativeChoiceOff);
			mergeHeadsToBodies(pendingHeadsToBodies, cumulativeHeadsToBodies);
			// cumulativeNoGoods has been GC'd by gcRetractedState if applicable; hand off directly.
			return new LinkedHashMap<>(cumulativeNoGoods);
		}
		return freshNoGoods;
	}

	@Override
	public Pair<Map<Integer, Integer>, Map<Integer, Integer>> getChoiceAtoms() {
		Pair<Map<Integer, Integer>, Map<Integer, Integer>> result = new ImmutablePair<>(
				new LinkedHashMap<>(pendingChoiceOn),
				new LinkedHashMap<>(pendingChoiceOff));
		pendingChoiceOn.clear();
		pendingChoiceOff.clear();
		return result;
	}

	@Override
	public Map<Integer, Set<Integer>> getHeadsToBodies() {
		Map<Integer, Set<Integer>> result = deepCopyHeadsToBodies(pendingHeadsToBodies);
		pendingHeadsToBodies.clear();
		return result;
	}

	@Override
	public AnswerSet assignmentToAnswerSet(Iterable<Integer> trueAtoms) {
		return delegate.assignmentToAnswerSet(trueAtoms);
	}

	@Override
	public void updateAssignment(IntIterator it) {
		delegate.updateAssignment(it);
	}

	@Override
	public void forgetAssignment(int[] atomIds) {
		delegate.forgetAssignment(atomIds);
	}

	@Override
	public int register(NoGood noGood) {
		// Solver-registered nogoods (enumeration nogoods, learned nogoods) are NOT added to the
		// cumulative replay set. They encode the solver's per-call answer-set enumeration history
		// and choice-combination exclusions that would invalidly block valid answer sets after the
		// program is extended in a subsequent shot. A fresh solver re-discovers enumeration nogoods
		// as it iterates.
		return delegate.register(noGood);
	}

	private static void mergeHeadsToBodies(Map<Integer, Set<Integer>> target, Map<Integer, Set<Integer>> src) {
		for (Map.Entry<Integer, Set<Integer>> e : src.entrySet()) {
			target.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
		}
	}

	private static Map<Integer, Set<Integer>> deepCopyHeadsToBodies(Map<Integer, Set<Integer>> src) {
		Map<Integer, Set<Integer>> copy = new LinkedHashMap<>();
		for (Map.Entry<Integer, Set<Integer>> e : src.entrySet()) {
			copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
		}
		return copy;
	}
}
