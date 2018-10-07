package edu.kit.nildumu;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import edu.kit.nildumu.Runner.TestCase;
import edu.kit.nildumu.prog.SimpleTestBed;
import edu.kit.nildumu.prog.SimpleTestBed2;

/**
 * Basic tests using the Runner class
 */
public class SimpleRunnerTests {

	public static Stream<Arguments> simpleTestsSupplier(){
		return Runner.testCases(SimpleTestBed.class);
	}
	
	@ParameterizedTest
	@MethodSource("simpleTestsSupplier")
	void test(TestCase testCase, String handlerProp) {
		Runner.test(testCase, handlerProp);
	}
	
	public static Stream<Arguments> simpleTestsSupplier2(){
		return Runner.testCases(SimpleTestBed2.class);
	}
	
	@ParameterizedTest
	@MethodSource("simpleTestsSupplier2")
	void test2(TestCase testCase, String handlerProp) {
		Runner.test(testCase, handlerProp);
	}
	
	public static void main(String[] args) {
		Runner.testCases(SimpleTestBed.class).forEach(a -> Runner.test((TestCase)a.get()[0], (String)a.get()[1]));
	}
}
