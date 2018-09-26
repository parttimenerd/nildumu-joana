package edu.kit.nildumu;

import edu.kit.joana.api.IFCAnalysis;
import edu.kit.joana.wala.core.SDGBuilder;

public class BuildResult {
	public final SDGBuilder builder;
	public BuildResult(SDGBuilder builder, IFCAnalysis analysis) {
		super();
		this.builder = builder;
		this.analysis = analysis;
	}
	public final IFCAnalysis analysis;
}
