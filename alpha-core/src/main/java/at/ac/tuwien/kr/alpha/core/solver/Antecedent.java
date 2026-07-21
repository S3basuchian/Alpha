package at.ac.tuwien.kr.alpha.core.solver;

/**
 * An interface to reasons of implications as used internally by the solver. This is a lightweight {@link at.ac.tuwien.kr.alpha.core.common.NoGood} that only
 * provides an array of literals (in some order) and has an activity that may change.
 *
 * Copyright (c) 2019, the Alpha Team.
 */
public interface Antecedent {

	int[] getReasonLiterals();

	void bumpActivity();

	void decreaseActivity();

	/**
	 * @return {@code true} if this antecedent originates from a {@link at.ac.tuwien.kr.alpha.core.common.NoGoodInterface.Type#TRANSIENT}
	 * nogood (a solution-blocking nogood, or a learned nogood re-classified as enumeration-scoped). Conflict
	 * analysis uses this to detect when a learned nogood resolved through an enumeration nogood and is therefore
	 * sound only for the answer-set-blocked program. Defaults to {@code false}.
	 */
	default boolean fromTransient() {
		return false;
	}

}
