/*
 * Copyright (c) 2018-2020, the Alpha Team.
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
package at.ac.tuwien.kr.alpha.core.common;

import at.ac.tuwien.kr.alpha.core.solver.Antecedent;

public interface NoGoodInterface extends Iterable<Integer> {

	/**
	 * Returns the literal at the given index.
	 * @param index the index position within the NoGood.
	 * @return the literal at the index.
	 */
	int getLiteral(int index);

	/**
	 * Returns whether the NoGood has a head.
	 * @return true if the NoGood has a head.
	 */
	boolean hasHead();

	/**
	 * Returns the head literal of the NoGood, if present.
	 * @return the head literal if the NoGood has a head, otherwise an arbitrary integer.
	 */
	int getHead();

	/**
	 * Returns the size, i.e., number of literals, in the NoGood.
	 * @return the size of the NoGood.
	 */
	int size();

	default boolean isUnary() {
		return size() == 1;
	}

	default boolean isBinary() {
		return size() == 2;
	}

	Antecedent asAntecedent();

	Type getType();

	/**
	 * The possible nogood types
	 */
	enum Type {
		/**
		 * Unremovable nogood from the input program
		 */
		STATIC,

		/**
		 * Removable support nogood from the input program
		 */
		SUPPORT,

		/**
		 * Removable nogood learnt from a conflict
		 */
		LEARNT,

		/**
		 * Nogood containing solver-internal atoms
		 */
		INTERNAL,

		/**
		 * A <em>transient</em> nogood (the paper's N_t in "Boosting ASP by Incremental Lazy Grounding"): valid
		 * only within the current solving shot and purged before the next one, because it is not
		 * program-monotone (a later add/retract can invalidate it). Three kinds share this tag:
		 * <ul>
		 *   <li><b>enumeration</b> nogoods, blocking an already-found answer set (added by
		 *       {@code DefaultSolver.prepareForSubsequentAnswerSet});</li>
		 *   <li><b>completion</b> (Clark "only-if"/support) nogoods emitted in session mode; and</li>
		 *   <li><b>foundedness</b> (justification) nogoods.</li>
		 * </ul>
		 * A learned nogood that resolves through any of these inherits the tag (see {@code NoGood.asTransient()})
		 * so it is dropped too. All are removed by {@code NoGoodStore.purgeTransientNoGoods()} at the shot
		 * boundary (Algorithm 2 tagging / Lemma 3).
		 */
		TRANSIENT,
	}
}
