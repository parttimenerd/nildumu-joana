package edu.kit.nildumu;

import edu.kit.joana.ifc.sdg.graph.SDGNode;

@FunctionalInterface
public interface NodeEvaluator {
	/**
	 * Evaluates the passed node
	 * 
	 * @param node passed node
	 * @return true if the result of the evaluate was the different
	 * compared to the last evaluation
	 */
	public boolean evaluate(SDGNode node);
}
