/**
 * Copyright (c) 2016-2018, the Alpha Team.
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
package at.ac.tuwien.kr.alpha.core.grounder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import at.ac.tuwien.kr.alpha.api.programs.Predicate;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;
import at.ac.tuwien.kr.alpha.api.programs.literals.Literal;
import at.ac.tuwien.kr.alpha.commons.substitutions.Instance;

/**
 * WorkingMemory keeping track of all ground instances assigned by a grounder
 */
public class WorkingMemory {

	/**
	 * The actual workingMemory containing a pair of instance storages (positive and negative) for each predicate
	 */
	protected HashMap<Predicate, ImmutablePair<IndexedInstanceStorage, IndexedInstanceStorage>> workingMemory = new HashMap<>();


	/**
	 * List of modified (i.e., newly added) instances recorded in the WorkingMemory since the last reset()
	 */
	private HashSet<IndexedInstanceStorage> modifiedWorkingMemories = new LinkedHashSet<>();

	public boolean contains(Predicate predicate) {
		return workingMemory.containsKey(predicate);
	}

	/**
	 * Initializes the workingMemory for one predicate and creates the IndexedInstanceStorage for the positive and
	 * negative representations.
	 * Also initializes all index positions of the predicate. However, since the instances' list of the storage will
	 * still be empty at this point, the instances must still be added via addInstance
	 *
	 * @param predicate
	 */
	public void initialize(Predicate predicate) {
		if (workingMemory.containsKey(predicate)) {
			return;
		}

		IndexedInstanceStorage pos = new IndexedInstanceStorage(predicate, true);
		IndexedInstanceStorage neg = new IndexedInstanceStorage(predicate, false);
		// Index all positions of the storage (may impair efficiency)
		for (int i = 0; i < predicate.getArity(); i++) {
			pos.addIndexPosition(i);
			neg.addIndexPosition(i);
		}

		workingMemory.put(predicate, new ImmutablePair<>(pos, neg));
	}

	public IndexedInstanceStorage get(Literal literal) {
		return get(literal.getAtom(), !literal.isNegated());
	}

	public IndexedInstanceStorage get(Atom atom, boolean value) {
		return get(atom.getPredicate(), value);
	}

	public IndexedInstanceStorage get(Predicate predicate, boolean value) {
		ImmutablePair<IndexedInstanceStorage, IndexedInstanceStorage> pair = workingMemory.get(predicate);
		if (value) {
			return pair.getLeft();
		} else {
			return pair.getRight();
		}
	}

	/**
	 * Adds a record of the instantiated atom to the WorkingMemory.
	 * * Called in the main loop of the NaiveGrounder each time the WritableAssignment changes
	 * * Furthermore, called if the StratifiedEvaluation fires a rule for the head atom of the rule
	 *
	 * @param atom
	 * @param value
	 */
	public void addInstance(Atom atom, boolean value) {
		addInstance(atom.getPredicate(), value, new Instance(atom.getTerms()));
	}

	public void addInstance(Predicate predicate, boolean value, Instance instance) {
		IndexedInstanceStorage storage = get(predicate, value);

		if (!storage.containsInstance(instance)) {
			storage.addInstance(instance);
			modifiedWorkingMemories.add(storage);
		}
	}

	/**
	 * Adds a record of the instantiated atom to the WorkingMemory.
	 * * Called in NaiveGrounder during the bootstrap process
	 * * Also used in StratifiedEvaluation (which uses a separately instantiated WorkingMemory for some reason) during
	 * the creation of the InternalProgram
	 *
	 * @param predicate
	 * @param value
	 * @param instances
	 */
	public void addInstances(Predicate predicate, boolean value, Iterable<Instance> instances) {
		IndexedInstanceStorage storage = get(predicate, value);

		for (Instance instance : instances) {
			if (!storage.containsInstance(instance)) {
				storage.addInstance(instance);
				modifiedWorkingMemories.add(storage);
			}
		}
	}

	public void reset() {
		modifiedWorkingMemories = new LinkedHashSet<>();
	}

	/**
	 * Used in various places by the NaiveGrounder and StratifiedEvaluation to get a list of recently added/modified
	 * instances. Usually reset() is called after the list is processed
	 *
	 * @return
	 */
	public Set<IndexedInstanceStorage> modified() {
		return modifiedWorkingMemories;
	}
}
