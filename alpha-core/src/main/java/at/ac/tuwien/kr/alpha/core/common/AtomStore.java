/*
 * Copyright (c) 2016-2020, the Alpha Team.
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

import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomOf;
import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.isNegated;

import java.util.Iterator;

import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;
import at.ac.tuwien.kr.alpha.core.solver.AtomCounter;

/**
 * Translates atoms between integer (solver) and object (grounder) representation.
 */
public interface AtomStore {

	/**
	 * Returns true whenever the atom is a valid choice point (i.e., it represents a rule body).
	 * @param atom
	 * @return
	 */
	boolean isAtomChoicePoint(int atom);

	/**
	 * Returns the highest atomId in use.
	 * @return the highest atomId in use.
	 */
	int getMaxAtomId();

	/**
	 * Translates an atom represented as int into an Atom object.
	 * @param atom the atom to translate.
	 * @return the Atom object represented by the int.
	 */
	Atom get(int atom);

	/**
	 * Translates an atom represented as Atom object into an int.
	 * @param atom the Atom object to translate.
	 * @return the int representing the Atom object.
	 */
	int get(Atom atom);

	/**
	 * If the given ground atom is not already stored, associates it with a new integer (ID) and stores it, else
	 * returns the current associated atom ID. Hence, multiple calls with the same parameter will return the same
	 * value.
	 * @param groundAtom the ground atom to look up in the store.
	 * @return the integer ID of the ground atom, possibly newly assigned.
	 */
	int putIfAbsent(Atom groundAtom);

	/**
	 * Returns whether the given ground atom is known to the AtomStore.
	 * @param groundAtom the ground atom to test.
	 * @return true if the ground atom is already associated an integer ID.
	 */
	boolean contains(Atom groundAtom);

	/**
	 * Remove the given atom id from the store: its slot becomes null and {@link #contains} returns
	 * false for the previously-stored atom. The id is not re-used (allocations remain monotone), but
	 * the atom→id reverse map entry is cleared so a subsequent {@link #putIfAbsent} for the same
	 * structural atom allocates a fresh id.
	 *
	 * <p>Used by the session-mode retraction GC path: when a fact is retracted and its dependent
	 * ground rules become unfireable, the corresponding body atoms (β) are removed so that
	 * re-grounding upon re-addition produces new ids and new nogoods through the normal pipeline
	 * (avoiding the "already grounded, skip" short-circuit in {@code NoGoodGenerator}).
	 *
	 * <p>Callers must guarantee that no live solver references the removed id — in the session-mode
	 * retraction path this is enforced by falling back to a fresh-solver rebuild whenever the GC
	 * physically removes atoms from the store.
	 *
	 * @param atomId the id to release; no-op if the id is not currently bound
	 */
	default void removeAtom(int atomId) {
		throw new UnsupportedOperationException("removeAtom not supported by this AtomStore implementation");
	}

	String atomToString(int atom);

	default String literalToString(int literal) {
		return (isNegated(literal) ? "-" : "+") + "(" + atomToString(atomOf(literal)) + ")";
	}

	/**
	 * Prints the NoGood such that literals are structured atoms instead of integers.
	 * @param noGood the nogood to translate
	 * @return the string representation of the NoGood.
	 */
	default <T extends NoGoodInterface> String noGoodToString(T noGood) {
		StringBuilder sb = new StringBuilder();

		if (noGood.hasHead()) {
			sb.append("*");
		}
		sb.append("{");

		for (Iterator<Integer> iterator = noGood.iterator(); iterator.hasNext();) {
			sb.append(literalToString(iterator.next()));

			if (iterator.hasNext()) {
				sb.append(", ");
			}
		}

		sb.append("}");

		return sb.toString();
	}

	AtomCounter getAtomCounter();
}
