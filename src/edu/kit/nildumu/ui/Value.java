package edu.kit.nildumu.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import edu.kit.joana.ui.annotations.Source;

/**
 * Annotates sources ({@link Source}) with a value.
 * It allows to configure which bits are seen as unknown.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface Value {
	String value() default "0buuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu";
}
