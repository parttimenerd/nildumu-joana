package edu.kit.nildumu;

import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class SimpleTests {

	@ParameterizedTest
	@CsvSource({"Simple4, 2", "SimpleIf, 1", "SimpleMod, 3", "Simple5, 1"})
	public void testLeakage(String className, int leakage) throws ClassNotFoundException {
		Program program = TestUtil.load(className);
		program.fixPointIteration();
		program.context.storeLeakageGraphs();
		new ContextMatcher(program.context).leaks(leakage).run();
	}
	
	@Test
	public void testWhile() throws ClassNotFoundException {
		testLeakage("SimpleWhile", 1);
	}
	
	@ParameterizedTest
	@CsvSource({"SimpleFuncCall, all, 1", "SimpleFuncCall, call_string, 1", "SimpleFuncCall, summary, 1"})
	public void testFuncModeLeakage(String className, String handler, int leakage) throws ClassNotFoundException {
		Program program = TestUtil.load(className);
		program.setMethodInvocationHandler(handler).fixPointIteration();
		program.context.storeLeakageGraphs();
		new ContextMatcher(program.context).leaks(leakage).run();
	}
	
	@ParameterizedTest
	@CsvSource({"SimpleFuncCall, 1"})
	public void testFuncModeLeakage(String className, int leakage) throws ClassNotFoundException {
		assertAll(MethodInvocationHandler.getExamplePropLines().stream().map(p -> () -> testFuncModeLeakage(className, p, leakage)));
	}
}
