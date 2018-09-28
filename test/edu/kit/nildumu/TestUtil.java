package edu.kit.nildumu;

import java.util.HashMap;
import java.util.Map;

public class TestUtil {

	private static Map<Class<?>, BuildResult> resPerClass = new HashMap<>();
	
	
	static <T> Program load(Class<T> clazz) {
		if (!resPerClass.containsKey(clazz)) {
			resPerClass.put(clazz, new Builder().entry(clazz).enableDumpAfterBuild().buildOrDie());
		}
		return new Program(resPerClass.get(clazz));
	}
	
	static Program load(String className) {
		try {
			return load(Class.forName("edu.kit.nildumu.prog." + className));
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
	
}
