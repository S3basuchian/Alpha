package at.ac.tuwien.kr.alpha.api;

import java.util.function.Predicate;
import java.util.stream.Stream;

import at.ac.tuwien.kr.alpha.api.programs.ASPCore2Program;
import at.ac.tuwien.kr.alpha.api.programs.atoms.Atom;

/**
 * A long-lived solving session that supports basic incremental ASP solving: the program is built up across multiple
 * {@link #add} calls, and answer sets can be computed at any point via {@link #solve} or {@link #solve(Predicate)}.
 *
 * Conceptually, this is the lazy-grounding analogue of clingo's multi-shot {@code Control} object. Solver state
 * — learned nogoods and the branching heuristic's activity scores — is preserved across {@link #solve} calls in
 * the common case (monotone fact/rule additions). The grounder + atom store survive across all shots; the
 * solver itself is rebuilt only when fact retraction has invalidated assignment state.
 *
 * Typical usage:
 *
 * <pre>{@code
 * try (AlphaSession session = alpha.newSession()) {
 *     session.add("node(1). node(2). edge(1,2).");
 *     Set<AnswerSet> first = session.solve().collect(Collectors.toSet());
 *
 *     session.add("node(3). edge(2,3).");
 *     Set<AnswerSet> second = session.solve().collect(Collectors.toSet());
 * }
 * }</pre>
 *
 * The current contract:
 * <ul>
 *   <li>{@link #add} accepts <em>rules and facts</em> before the first {@link #solve} (i.e., the initial
 *       program). After the first solve, {@link #add} accepts <em>facts only</em>; passing a fragment that
 *       contains rules throws {@link IllegalStateException}. Use {@link #reset} to discard accumulated
 *       state if you need to redefine rules.</li>
 *   <li>{@link #removeFacts} retracts previously-added <em>facts</em> (rules and constraints cannot be
 *       retracted in this prototype). The next {@link #solve} call will re-derive answer sets under the
 *       remaining facts. Removing a fact that was never added is a no-op.</li>
 *   <li>{@link #solve} recomputes answer sets of the currently-accumulated program. Multiple calls without an
 *       intervening {@link #add} or {@link #removeFacts} are equivalent.</li>
 *   <li>The session is not thread-safe.</li>
 * </ul>
 *
 * <p>The rule-add restriction closes a learned-nogood soundness gap: adding a rule whose head predicate
 * already has rules from a previous shot would weaken that predicate's existing support and silently
 * invalidate learned nogoods derived against it. Lifting the restriction requires per-learned-nogood
 * provenance tracking; planned as future work.
 *
 * <p>Implementation note on incremental fast paths:
 * <ul>
 *   <li><b>Fact / constraint additions</b> keep everything: grounder, atom store, NoGoodStore (incl.
 *       learned nogoods), and branching heuristic activity scores. Closing assignments from the
 *       previous shot's answer set are cleared; enumeration nogoods accumulated when the previous
 *       shot enumerated all answer sets are purged in-place via
 *       {@code NoGoodStore.purgeEnumerationNoGoods()}.</li>
 *   <li><b>Fact retraction</b> keeps the grounder + atom store + VSIDS scores + the live solver, but
 *       <em>discards all learned nogoods</em> via a fresh rebuild (learned nogoods die with the discarded solver):
 *       any learned nogood whose derivation chain referenced the retracted fact's unit nogood may
 *       be unsound in the reduced program (it could wrongly exclude valid answer sets). The dl-0
 *       assignment is dropped and re-derived by full re-propagation over the surviving fact units.
 *       This matches DLV2's {@code Incremental-DLV2} behavior of not preserving learning across
 *       shots, while still preserving VSIDS, the grounder, and the live solver. It is the single
 *       retraction path — dead ground rules are left inert (β forced false), never removed.</li>
 *   <li><b>Full rebuild</b> (fresh atom store + grounder + solver) is taken only on the first solve,
 *       on {@link #reset}, and on a change of the answer-set filter.</li>
 * </ul>
 */
public interface AlphaSession {

	/**
	 * Parse the given ASP code and append all of its rules, facts, and inline directives to this session's accumulated
	 * program. Before the first {@link #solve}, any fragment is accepted; after the first solve, only fact-only
	 * fragments are accepted (rule-bearing fragments throw {@link IllegalStateException}).
	 *
	 * @param aspCode an ASP-Core2 program fragment
	 * @throws IllegalStateException if called after the first {@link #solve} with a rule-bearing fragment
	 */
	void add(String aspCode);

	/**
	 * Append an already-parsed program to this session's accumulated program. Before the first {@link #solve},
	 * any program is accepted; after the first solve, only fact-only programs are accepted (rule-bearing
	 * programs throw {@link IllegalStateException}).
	 *
	 * @param program a parsed {@link ASPCore2Program}
	 * @throws IllegalStateException if called after the first {@link #solve} with a rule-bearing program
	 */
	void add(ASPCore2Program program);

	/**
	 * Retract a single previously-added fact from this session's accumulated program. Has no effect if the
	 * fact was never added (or has already been removed). Rules cannot be retracted via this method.
	 *
	 * @param fact the fact to retract
	 */
	void removeFacts(Atom fact);

	/**
	 * Retract a collection of previously-added facts. Facts not present in the accumulated program are
	 * silently ignored.
	 *
	 * @param facts the facts to retract
	 */
	void removeFacts(Iterable<Atom> facts);

	/**
	 * Parse the given ASP code as a fact-only fragment and retract each parsed fact from this session's
	 * accumulated program. Throws if the fragment contains any rules or constraints — use {@link #reset}
	 * to discard rules.
	 *
	 * @param aspCode an ASP-Core2 fragment consisting only of facts
	 */
	void removeFacts(String aspCode);

	/**
	 * Retract every fact occurring in the given parsed program from this session's accumulated program.
	 * Rules in the supplied program are ignored.
	 *
	 * @param program a parsed program whose facts should be retracted
	 */
	void removeFacts(ASPCore2Program program);

	/**
	 * @return a snapshot of the currently-accumulated program. The returned program is independent of the session;
	 *         subsequent {@link #add} calls do not modify it.
	 */
	ASPCore2Program getProgram();

	/**
	 * Compute answer sets of the currently-accumulated program.
	 *
	 * @return a {@link Stream} of {@link AnswerSet}s
	 */
	Stream<AnswerSet> solve();

	/**
	 * Compute answer sets of the currently-accumulated program, filtering the predicates shown in each answer set.
	 *
	 * @param filter predicate selecting which ASP predicates appear in the returned answer sets
	 * @return a {@link Stream} of {@link AnswerSet}s
	 */
	Stream<AnswerSet> solve(Predicate<at.ac.tuwien.kr.alpha.api.programs.Predicate> filter);

	/**
	 * Discard all accumulated rules and facts. After {@link #reset}, the session is in the same state as a freshly
	 * created one.
	 */
	void reset();

}
