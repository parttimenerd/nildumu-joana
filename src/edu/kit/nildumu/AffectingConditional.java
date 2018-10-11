package edu.kit.nildumu;

import com.google.common.base.Objects;

import edu.kit.joana.ifc.sdg.graph.SDGNode;

/**
 * Models the conditional that a SDGNode directly control depends on
 * and the branch that the node is part of
 */
public class AffectingConditional {
	
	public final SDGNode conditional;
	
	/**
	 * Value of the conditional node, that yields
	 * to the execution of a specific node
	 */
	public final boolean value;

	public AffectingConditional(SDGNode conditional, boolean value) {
		this.conditional = conditional;
		this.value = value;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", value ? "" : "!", conditional.getLabel());
	}
	
	@Override
	public int hashCode() {
		return Objects.hashCode(conditional, value);
	}
	
	@Override
	public boolean equals(Object obj) {
		return obj instanceof AffectingConditional && 
				conditional.equals(((AffectingConditional)obj).conditional) &&
				value == ((AffectingConditional)obj).value;
	}
}
