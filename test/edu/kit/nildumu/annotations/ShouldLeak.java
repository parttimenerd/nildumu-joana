package edu.kit.nildumu.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expresses how much a program should leak
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface ShouldLeak {
	int atMost() default -1;
	
	int atLeast() default -1;
	
	int exactly() default -1;
	
	String bits() default "";
}
