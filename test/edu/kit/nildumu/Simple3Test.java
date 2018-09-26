package edu.kit.nildumu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import edu.kit.nildumu.prog.Simple3;

class Simple3Test {
	
	private Program program;
	
	@BeforeEach
	public void init() {
		program = TestUtil.load(Simple3.class);
	}
	
	@Test
	void testLeakageComputation() {
		program.fixPointIteration();
		program.context.storeLeakageGraphs();
		new ContextMatcher(program.context).leaks(2).run();
	}
}
