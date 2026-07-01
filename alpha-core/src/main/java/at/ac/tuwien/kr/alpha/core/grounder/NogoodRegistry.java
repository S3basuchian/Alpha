package at.ac.tuwien.kr.alpha.core.grounder;

import java.util.LinkedHashMap;
import java.util.Map;

import at.ac.tuwien.kr.alpha.commons.util.IntIdGenerator;
import at.ac.tuwien.kr.alpha.core.common.NoGood;

public class NogoodRegistry {
	private static final IntIdGenerator ID_GENERATOR = new IntIdGenerator();

	private Map<NoGood, Integer> registeredIdentifiers = new LinkedHashMap<>();

	/**
	 * Helper methods to analyze average nogood length.
	 * @return
	 */
	public float computeAverageNoGoodLength() {
		int totalSizes = 0;
		for (Map.Entry<NoGood, Integer> noGoodEntry : registeredIdentifiers.entrySet()) {
			totalSizes += noGoodEntry.getKey().size();
		}
		return ((float) totalSizes) / registeredIdentifiers.size();
	}

	void register(Iterable<NoGood> noGoods, Map<Integer, NoGood> difference) {
		for (NoGood noGood : noGoods) {
			// Check if noGood was already derived earlier, add if it is new
			if (!registeredIdentifiers.containsKey(noGood)) {
				int noGoodId = ID_GENERATOR.getNextId();
				registeredIdentifiers.put(noGood, noGoodId);
				difference.put(noGoodId, noGood);
			}
		}
	}

	int register(NoGood noGood) {
		if (!registeredIdentifiers.containsKey(noGood)) {
			int noGoodId = ID_GENERATOR.getNextId();
			registeredIdentifiers.put(noGood, noGoodId);
			return noGoodId;
		}
		return registeredIdentifiers.get(noGood);
	}

	/**
	 * Drop the given nogood ids from the registry. After this call, registering an equivalent NoGood
	 * via {@link #register(NoGood)} will allocate a fresh id (rather than returning the dropped one).
	 *
	 * Used by the session-mode retraction path to allow nogoods that were tied to a now-retracted
	 * fact to be re-derived (with new ids) if the fact is later re-added.
	 *
	 * @param ids the nogood ids to forget
	 */
	void forget(java.util.Set<Integer> ids) {
		if (ids.isEmpty()) {
			return;
		}
		registeredIdentifiers.entrySet().removeIf(e -> ids.contains(e.getValue()));
	}
}
