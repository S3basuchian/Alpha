package at.ac.tuwien.kr.alpha.core.solver;

/**
 * Thrown from the solver's search loop when the solving thread has been interrupted, to abort an
 * in-progress solve promptly (e.g. under a caller-imposed per-solve timeout). Unchecked so it
 * propagates cleanly out of the answer-set {@link java.util.stream.Stream} without changing method
 * signatures; it is only ever raised in response to an explicit {@link Thread#interrupt()} and has no
 * effect on normal, non-interrupted solving.
 */
public class SolverAbortedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SolverAbortedException(String message) {
		super(message);
	}
}
