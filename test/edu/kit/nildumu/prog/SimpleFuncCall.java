package edu.kit.nildumu.prog;

import edu.kit.joana.ui.annotations.EntryPoint;
import edu.kit.joana.ui.annotations.Level;
import edu.kit.joana.ui.annotations.Source;
import edu.kit.nildumu.ui.Config;

import static edu.kit.nildumu.ui.CodeUI.*;

/**
 * Extends Simple with a loop
 */
public class SimpleFuncCall {
	
	public static void main(String[] args) {
		program(10);
	}
	
	@Config(intWidth=1)
	@EntryPoint
	public static void program(@Source(level=Level.HIGH) int h) {
		output(run(h, h | 0) | 0, "l");
	}
	
	public static int run(int a, int b) {
		return a | b;
	}
}
