package edu.kit.nildumu.prog;

import edu.kit.joana.ui.annotations.EntryPoint;
import edu.kit.joana.ui.annotations.Level;
import edu.kit.joana.ui.annotations.Source;
import static edu.kit.nildumu.ui.CodeUI.*;

public class Simple {
	
	public static void main(String[] args) {
		program(1);
	}
	
	@EntryPoint
	public static void program(@Source(level=Level.HIGH) int h) {
		output(h, "l");
	}
}
