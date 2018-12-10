package edu.kit.nildumu.ui;

public class CodeUI {
	
	/**
	 * Leaks the argument to an attacker of the given level
	 * 
	 * @param o information to leak
	 * @param level attacker level
	 */
	@OutputMethod
	public static void output(int o, String level) {
		
	}
	
	/**
	 * Leaks the argument to an attacker of the lowest level
	 * 
	 * @param o information to leak
	 */
	@OutputMethod
	public static void leak(int o) {
		
	}
	
	/**
	 * Leaks the argument to an attacker of the lowest level
	 * 
	 * @param o information to leak
	 */
	@OutputMethod
	public static void leak(boolean o) {
		
	}
}
