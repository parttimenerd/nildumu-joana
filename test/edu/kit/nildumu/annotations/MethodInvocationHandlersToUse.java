package edu.kit.nildumu.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface MethodInvocationHandlersToUse {
	/**
	 * Handlers to exclude (only their names)
	 */
	String[] exclude() default {};
	
	/**
	 * Additional handlers to use, "default" == all default
	 */
	String[] value() default {"default"};
}
