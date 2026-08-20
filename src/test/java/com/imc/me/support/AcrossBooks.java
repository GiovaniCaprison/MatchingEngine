package com.imc.me.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the test once per book implementation.
 *
 * <p>A correctness test that only covers one implementation stops being a correctness test the
 * moment there is a second one, so behaviour tests carry this rather than naming a book.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest(name = "{0}")
@MethodSource("com.imc.me.support.BookImplementations#all")
public @interface AcrossBooks {}
