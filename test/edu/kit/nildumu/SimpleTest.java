package edu.kit.nildumu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ibm.wala.ssa.ISSABasicBlock;

import edu.kit.nildumu.Lattices.BasicSecLattice;
import edu.kit.nildumu.Lattices.Bit;
import edu.kit.nildumu.Program.NextBlockFilter;
import edu.kit.nildumu.prog.Simple;
import edu.kit.nildumu.util.Util.Box;

class SimpleTest {
	private Program program;
	
	@Test
	@BeforeEach
	public void init() {
		program = TestUtil.load(Simple.class);
	}
	
	@Test
	void testTryWorkListRun() {
		program.trialWorkListRun(program.main.entry);
	}
	
	@Test
	void testTryWorkListWithOutputHandler() {
		Box<Boolean> visitedOutput = new Box<>(false);
		program.context.workList(program.main.entry, n -> {
			if (n.getLabel().contains("output")) {
				visitedOutput.val = true;
			}
			return Program.print(n);
		}, new NextBlockFilter() {
			
			@Override
			public boolean test(ISSABasicBlock t) {
				return true;
			}
			
			@Override
			public void clear() {
			}
		});
		assertAll(
				() -> assertFalse(visitedOutput.val, "No output call site visited"),
				() -> assertTrue(program.context.output.hasValuesForSec(BasicSecLattice.LOW),
						"No low output value registered")
				);
	}
	
	@Test
	void testSettingOfMainParamValues() {
		assertAll(() -> assertTrue(program.context.input.getBits(BasicSecLattice.HIGH).size() > 0, 
				"Output contains some bits of level low"),
		() -> assertTrue(program.context.input.getBits(BasicSecLattice.HIGH).stream().allMatch(Bit::isUnknown),
				"All output bits of level low are unknown"));
	}
	
	@Test
	void testLeakageComputation() {
		program.fixPointIteration();
		program.context.storeLeakageGraphs();
		new ContextMatcher(program.context).leaks(32).run();
	}
}
