/**
 * Copyright (c) 2018-2019, the Alpha Team.
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

import at.ac.tuwien.kr.alpha.core.common.Assignment;
import at.ac.tuwien.kr.alpha.core.common.AtomStore;
import at.ac.tuwien.kr.alpha.core.common.AtomStoreImpl;
import at.ac.tuwien.kr.alpha.core.common.IntIterator;
import at.ac.tuwien.kr.alpha.core.test.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static at.ac.tuwien.kr.alpha.core.programs.atoms.Literals.atomToLiteral;
import static at.ac.tuwien.kr.alpha.core.solver.ThriceTruth.FALSE;
import static at.ac.tuwien.kr.alpha.core.solver.ThriceTruth.MBT;
import static at.ac.tuwien.kr.alpha.core.solver.ThriceTruth.TRUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Copyright (c) 2018-2020, the Alpha Team.
 */
public class TrailAssignmentTest {
	private final TrailAssignment assignment;

	public TrailAssignmentTest() {
		AtomStore atomStore = new AtomStoreImpl();
		TestUtils.fillAtomStore(atomStore, 20);
		assignment = new TrailAssignment(atomStore);
	}

	@BeforeEach
	public void setUp() {
		assignment.clear();
		assignment.growForMaxAtomId();
	}

	@Test
	public void assignThrowsExceptionOnNullTruth() {
		assertThrows(IllegalArgumentException.class, () -> {
			assignment.assign(0, null);
		});
	}

	@Test
	public void negativeAtomThrowsException() {
		assertThrows(IllegalArgumentException.class, () -> {
			assignment.assign(-1, null);
		});
	}

	@Test
	public void alreadyAssignedThrows() {
		assertNull(assignment.assign(1, MBT));
		assertNotNull(assignment.assign(1, FALSE));
	}

	@Test
	public void initializeDecisionLevelState() {
		assignment.assign(1, MBT);
		assignment.choose(2, MBT);
		assignment.choose(1, TRUE);
	}

	@Test
	public void checkToString() {
		assignment.assign(1, FALSE);
		assertEquals("[F_a(0)@0]", assignment.toString());

		assignment.assign(2, TRUE);
		assertEquals("[F_a(0)@0, T_a(1)@0]", assignment.toString());
	}

	@Test
	public void reassignGracefully() {
		assignment.assign(1, FALSE);
		assignment.assign(1, FALSE);
	}

	@Test
	public void assignAndBacktrack() {
		assignment.assign(1, MBT);
		assignment.assign(2, FALSE);
		assignment.assign(3, TRUE);

		assertEquals(MBT, assignment.getTruth(1));
		assertEquals(FALSE, assignment.getTruth(2));
		assertEquals(TRUE, assignment.getTruth(3));
		assertEquals(assignment.getTrueAssignments(), new HashSet<>(Collections.singletonList(3)));
		assertEquals(1, assignment.getMBTCount());

		assignment.choose(1, TRUE);

		assertEquals(TRUE, assignment.getTruth(1));
		assertEquals(assignment.getTrueAssignments(), new HashSet<>(Arrays.asList(3, 1)));
		assertEquals(0, assignment.getMBTCount());

		assignment.backtrack();

		assertEquals(MBT, assignment.getTruth(1));
		assertEquals(FALSE, assignment.getTruth(2));
		assertEquals(TRUE, assignment.getTruth(3));
		assertEquals(assignment.getTrueAssignments(), new HashSet<>(Collections.singletonList(3)));
		assertEquals(1, assignment.getMBTCount());

		assignment.choose(4, MBT);
		assignment.assign(5, MBT);

		assertEquals(MBT, assignment.getTruth(4));
		assertEquals(MBT, assignment.getTruth(5));

		assignment.backtrack();

		assertFalse(assignment.isAssigned(4));
		assertFalse(assignment.isAssigned(5));

		assignment.choose(4, TRUE);

		assertEquals(TRUE, assignment.getTruth(4));

		assignment.backtrack();

		assertNull(assignment.getTruth(4));
	}

	@Test
	public void assignmentsToProcess() {
		assignment.assign(1, MBT);

		Assignment.Pollable queue = assignment.getAssignmentsToProcess();
		assertEquals(1, queue.remove());

		assignment.choose(2, MBT);
		assignment.choose(1, TRUE);

		assertEquals(2, queue.remove());
		assertEquals(1, queue.peek());

		queue = assignment.getAssignmentsToProcess();
		assertEquals(1, queue.remove());
	}

	@Test
	public void newAssignmentsIteratorAndBacktracking() {
		IntIterator newAssignmentsIterator;

		assignment.assign(1, MBT);
		assignment.choose(2, MBT);

		newAssignmentsIterator = assignment.getNewPositiveAssignmentsIterator();

		assertEquals(1, newAssignmentsIterator.next());
		assertEquals(2, newAssignmentsIterator.next());
		assertFalse(newAssignmentsIterator.hasNext());


		assignment.choose(1, TRUE);
		assignment.backtrack();
		assignment.assign(3, FALSE);

		newAssignmentsIterator = assignment.getNewPositiveAssignmentsIterator();
		assertEquals(3, newAssignmentsIterator.next());
		assertFalse(newAssignmentsIterator.hasNext());
	}

	@Test
	public void newAssignmentsIteratorLowerDecisionLevelAndBacktracking() {
		IntIterator newAssignmentsIterator;

		assignment.choose(1, MBT);
		assignment.choose(2, MBT);
		assignment.assign(3, MBT, null, 1);
		assignment.backtrack();

		newAssignmentsIterator = assignment.getNewPositiveAssignmentsIterator();
		assertEquals(1, newAssignmentsIterator.next());
		assertEquals(3, newAssignmentsIterator.next());
		assertFalse(newAssignmentsIterator.hasNext());
	}

	@Test
	public void iteratorAndBacktracking() {
		Assignment.Pollable assignmentsToProcess = assignment.getAssignmentsToProcess();

		assignment.assign(1, MBT);
		assertEquals(1, assignmentsToProcess.remove());

		assignment.choose(2, MBT);
		assertEquals(2, assignmentsToProcess.remove());

		assignment.choose(1, TRUE);
		assertEquals(1, assignmentsToProcess.remove());

		assignment.backtrack();

		assignment.assign(3, FALSE);
		assertEquals(3, assignmentsToProcess.remove());
	}

	@Test
	public void mbtCounterAssignMbtToFalseOnLowerDecisionLevel() {
		assertNull(assignment.choose(1, TRUE));
		assertNull(assignment.choose(2, FALSE));

		assertNull(assignment.assign(3, MBT, null, 2));
		assertEquals(1, assignment.getMBTCount());

		assertNull(assignment.choose(4, TRUE));

		assertNotNull(assignment.assign(3, FALSE, null, 1));

		assignment.backtrack();
		assignment.backtrack();

		assertEquals(0, assignment.getMBTCount());
	}

	@Test
	public void unassignManyAtDecisionLevelZero_emptyAndNullAreNoOps() {
		assignment.assign(1, FALSE);
		assignment.assign(2, TRUE);
		assignment.unassignManyAtDecisionLevelZero(Collections.emptyList());
		assertEquals(FALSE, assignment.getTruth(1));
		assertEquals(TRUE, assignment.getTruth(2));
		assignment.unassignManyAtDecisionLevelZero(null);
		assertEquals(FALSE, assignment.getTruth(1));
		assertEquals(TRUE, assignment.getTruth(2));
	}

	@Test
	public void unassignManyAtDecisionLevelZero_clearsSpecifiedAtoms() {
		assignment.assign(1, FALSE);
		assignment.assign(2, TRUE);
		assignment.assign(3, FALSE);
		assignment.unassignManyAtDecisionLevelZero(Arrays.asList(1, 3));
		assertFalse(assignment.isAssigned(1));
		assertEquals(TRUE, assignment.getTruth(2));
		assertFalse(assignment.isAssigned(3));
	}

	@Test
	public void unassignManyAtDecisionLevelZero_handlesDuplicateInput() {
		assignment.assign(1, MBT);  // mbtCount = 1
		assertEquals(1, assignment.getMBTCount());
		assignment.unassignManyAtDecisionLevelZero(Arrays.asList(1, 1, 1));
		assertFalse(assignment.isAssigned(1));
		// mbtCount must drop exactly once even though atom 1 appears three times in the input.
		assertEquals(0, assignment.getMBTCount());
	}

	@Test
	public void unassignManyAtDecisionLevelZero_skipsHigherDecisionLevels() {
		assignment.assign(1, FALSE);  // dl 0
		assignment.choose(2, MBT);    // dl 1
		// Atom 2 is at dl 1; must be left alone.
		assignment.unassignManyAtDecisionLevelZero(Arrays.asList(1, 2));
		assertFalse(assignment.isAssigned(1));
		assertEquals(MBT, assignment.getTruth(2));
	}

	@Test
	public void unassignManyAtDecisionLevelZero_skipsUnassignedAtoms() {
		assignment.assign(1, FALSE);
		// Atom 5 is never assigned.
		assignment.unassignManyAtDecisionLevelZero(Arrays.asList(1, 5));
		assertFalse(assignment.isAssigned(1));
		assertFalse(assignment.isAssigned(5));
	}

	@Test
	public void unassignManyAtDecisionLevelZero_handlesMbtToTrueUpgradeTrailEntries() {
		// Atom 1 goes MBT then TRUE: it occupies two trail slots; both must be removed.
		assignment.assign(1, MBT);
		assignment.assign(1, TRUE);
		assignment.assign(2, FALSE);
		assertEquals(TRUE, assignment.getTruth(1));
		// trailSize before: 3 (MBT, TRUE upgrade, FALSE).
		assignment.unassignManyAtDecisionLevelZero(Collections.singletonList(1));
		assertFalse(assignment.isAssigned(1));
		assertEquals(FALSE, assignment.getTruth(2));
		// Iterators still walk a coherent trail.
		IntIterator it = assignment.getNewPositiveAssignmentsIterator();
		// Only positive (TRUE/MBT) assignments. After the un-assign, atom 2 is FALSE.
		// So no positive assignment should remain.
		assertFalse(it.hasNext());
	}

	@Test
	public void unassignWithDependents_seedsViaAntecedentPredicate() {
		// Simulate: atom 1 is forced TRUE at dl 0 by a structural fact (no antecedent).
		// Then a "non-unary enum nogood" propagates atom 2 = FALSE at dl 0, with that
		// nogood as antecedent. Its reason literal is atom 1, which is NOT a seed atom.
		// The reason-atom cascade alone would never reach atom 2; the antecedent predicate must.
		Antecedent purgedNoGood = new Antecedent() {
			private final int[] reasons = { atomToLiteral(1, true), atomToLiteral(2, false) };

			@Override
			public int[] getReasonLiterals() {
				return reasons;
			}

			@Override
			public void bumpActivity() {
			}

			@Override
			public void decreaseActivity() {
			}
		};
		assignment.assign(1, TRUE);                                // structural fact, no antecedent
		assignment.assign(2, FALSE, purgedNoGood, 0);              // propagation by the soon-to-be-purged nogood

		// No explicit seed atoms; the predicate alone must drive the un-assignment.
		assignment.unassignAtDecisionLevelZeroWithDependents(Collections.emptyList(),
				(atom, ant) -> ant == purgedNoGood);

		assertFalse(assignment.isAssigned(2));
		assertEquals(TRUE, assignment.getTruth(1));                // untouched: predicate didn't match
	}

	@Test
	public void unassignWithDependents_antecedentPredicateCascades() {
		// atom 1 forced FALSE at dl 0 by a "purged" nogood; atom 2 then derived at dl 0
		// from atom 1 via a different (structural) nogood. The cascade must reach atom 2
		// from the predicate-seeded atom 1 via the reason-atom chain.
		Antecedent purgedNoGood = new Antecedent() {
			private final int[] reasons = { atomToLiteral(1, true) };

			@Override
			public int[] getReasonLiterals() {
				return reasons;
			}

			@Override
			public void bumpActivity() {
			}

			@Override
			public void decreaseActivity() {
			}
		};
		Antecedent structural = new Antecedent() {
			private final int[] reasons = { atomToLiteral(1, false), atomToLiteral(2, false) };

			@Override
			public int[] getReasonLiterals() {
				return reasons;
			}

			@Override
			public void bumpActivity() {
			}

			@Override
			public void decreaseActivity() {
			}
		};
		assignment.assign(1, FALSE, purgedNoGood, 0);
		assignment.assign(2, FALSE, structural, 0);

		assignment.unassignAtDecisionLevelZeroWithDependents(Collections.emptyList(),
				(atom, ant) -> ant == purgedNoGood);

		assertFalse(assignment.isAssigned(1));
		assertFalse(assignment.isAssigned(2));                     // reached via the cascade
	}

	@Test
	public void numberOfAssignedAtoms() {
		assignment.assign(1, MBT);
		assertEquals(1, assignment.getNumberOfAssignedAtoms());
		assignment.assign(2, FALSE);
		assertEquals(2, assignment.getNumberOfAssignedAtoms());
		assignment.assign(3, MBT);
		assignment.assign(3, TRUE);
		assertEquals(3, assignment.getNumberOfAssignedAtoms());
		assignment.choose(1, TRUE);
		assertEquals(3, assignment.getNumberOfAssignedAtoms());
		assertEquals(1, assignment.getNumberOfAtomsAssignedSinceLastDecision());
		assignment.assign(5, MBT);
		assignment.assign(5, TRUE);
		assertEquals(2, assignment.getNumberOfAtomsAssignedSinceLastDecision());
	}
}
