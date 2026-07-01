package at.ac.tuwien.kr.alpha.core.solver;

import java.util.Collection;

import at.ac.tuwien.kr.alpha.core.common.NoGood;

/**
 * An interface defining the use of a NoGood store.
 *
 * Copyright (c) 2016-2019, the Alpha Team.
 */
public interface NoGoodStore {
	int LBD_NO_VALUE = -1;

	/**
	 * Adds a nogood with the given id.
	 * @param id the unique identifier of the nogood.
	 * @param noGood the nogood to add.
	 * @param lbd the literals block distance.
	 * @return {@code null} if the noGood was added without conflict, a {@link ConflictCause} describing
	 *         the conflict otherwise.
	 */
	ConflictCause add(int id, NoGood noGood, int lbd);

	/**
	 * Adds a NoGood with no LBD value set.
	 * @param id the unique identifier of the NoGood.
	 * @param noGood the NoGood to add.
	 * @return {@code null} if the noGood was added without conflict, a {@link ConflictCause} describing
* 	 *         the conflict otherwise.
	 */
	ConflictCause add(int id, NoGood noGood);

	/**
	 * Apply weak propagation and strong propagation. Propagation should stop as soon as some nogood is violated.
	 * @return some cause iff a conflict was reached or {@code null} otherwise
	 */
	ConflictCause propagate();

	/**
	 * After a call to {@link #propagate()} this method provides
	 * whether propagation was successful, i.e. at least one new
	 * assignment was inferred.
	 * @return {@code true} iff the last call to {@link #propagate()}
	 *         inferred at least one assignment, {@code false} otherwise.
	 */
	boolean didPropagate();

	void backtrack();

	void growForMaxAtomId(int maxAtomId);

	/**
	 * Tests whether a cleanup of the learned NoGoods database is appropriate and exectutes the cleaning if
	 * necessary.
	 */
	void cleanupLearnedNoGoods();

	/**
	 * Drops every nogood of {@link at.ac.tuwien.kr.alpha.core.common.NoGoodInterface.Type#ENUMERATION} from the
	 * store: removes them from watch lists, undoes any decision-level-0 assignments they forced, and resets
	 * the type/cardinality counters. Structural and learned nogoods are untouched.
	 *
	 * <p>Intended use is {@code DefaultSolver.resetForNewShot(Iterable)} in a {@code AlphaSession}: between shots,
	 * the session must purge enumeration nogoods because they are only valid for the program at the time
	 * the previous shot's answer set was found and would invalidly block valid answer sets of the
	 * (potentially extended) program of the next shot.
	 *
	 * <p>Pre: caller has already backjumped to decision level 0 — this method only handles the dl-0
	 * residue (unary enumeration nogoods that forced a literal at dl 0). It is a no-op if no enumeration
	 * nogoods are recorded.
	 */
	void purgeEnumerationNoGoods();

	/**
	 * Drops every learned nogood from the store (used by the in-place fact-retraction path). A learned
	 * nogood whose derivation depended on a now-retracted fact may be unsound in the reduced program, so
	 * all learning is discarded — the sound, conservative choice that needs no provenance tracking.
	 *
	 * <p>Pre: caller has already cleared the assignment (so learned unaries' dl-0 propagations are gone);
	 * this method only detaches watches and resets counters. Default is a no-op for stores that do not
	 * support incremental learned-nogood removal.
	 */
	default void dropAllLearnedNoGoods() {
		throw new UnsupportedOperationException("This NoGoodStore does not support dropAllLearnedNoGoods.");
	}

	/**
	 * Re-asserts the given unit (size-1) nogoods at decision level 0. Used by the in-place retraction path
	 * after clearing the assignment, to re-force the surviving facts and thereby re-trigger propagation of
	 * their structural consequences.
	 */
	default void reassertUnits(Collection<NoGood> units) {
		throw new UnsupportedOperationException("This NoGoodStore does not support reassertUnits.");
	}

	NoGoodCounter getNoGoodCounter();
}
