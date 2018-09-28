package edu.kit.nildumu.prog;

import edu.kit.joana.ui.annotations.EntryPoint;
import edu.kit.joana.ui.annotations.Level;
import edu.kit.joana.ui.annotations.Source;
import edu.kit.nildumu.ui.Config;

import static edu.kit.nildumu.ui.CodeUI.*;

/**
 * Extends Simple with a binary expression:
 * <code>
 * h input int h   = 0buu;
 * h input int h2  = 0buu;
 * l output int o = h | ;
 * </code>
 */
public class Simple4 {
	
	public static void main(String[] args) {
		program(1, 1);
	}
	
	@Config(intWidth=2)
	@EntryPoint
	public static void program(@Source(level=Level.HIGH) int h, @Source(level=Level.HIGH) int h2) {
		int a = 0b0 | h;
		output(h | a, "l");
	}
}
