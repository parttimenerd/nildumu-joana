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
@Config(intWidth=2)
public class SimpleIf {
	
	public static void main(String[] args) {
		program(10);
	}
	
	@EntryPoint
	public static void program(@Source(level=Level.HIGH) int h) {
		int o = 0;
		if (h < 10) {
			o = 1;
		}
		output(o, "l");
	}
}
