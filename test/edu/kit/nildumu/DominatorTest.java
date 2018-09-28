package edu.kit.nildumu;

import static edu.kit.nildumu.util.Util.set;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

public class DominatorTest {

	@Test
	public void testBasicDominatorGeneration() {
		Dominators<String> doms = new Dominators<>("bla", s -> {
			return Collections.singleton("bla");
		});
		assertAll(() -> assertEquals(1, doms.loopDepth("bla"), "Wrong loop depth for bla"),
				() -> assertTrue(doms.dominators("bla").contains("bla"), "bla dominates itself"));
	}

	@Test
	public void testMoreComplexCallGraphGeneration() {
		Dominators<String> doms = new Dominators<>("f", s -> {
			switch (s){
			case "f":
				return set("g", "z");
			case "g":
				return set("h", "g", "f");
			case "h":
				return set("g");
			case "z":
				return set();
			}
			return set();
		});
		assertAll(() -> assertEquals(2, doms.loopDepth("h"), "Wrong loop depth for h"),
				() -> assertTrue(doms.dominators("h").contains("f"),
						"f dominates h"));
	}
}
