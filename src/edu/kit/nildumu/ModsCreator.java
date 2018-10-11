package edu.kit.nildumu;

import edu.kit.nildumu.Lattices.Bit;

@FunctionalInterface
public interface ModsCreator {
	public Mods apply(Context context, Bit bit, Bit assumedValue);
}
