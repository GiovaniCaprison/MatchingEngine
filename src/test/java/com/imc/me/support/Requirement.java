package com.imc.me.support;

import java.lang.annotation.*;

/**
 * Traceability marker tying a test to one or more spec requirement IDs (e.g. "FR-3.1"). Place it on
 * a test method (explicit layer, one ID each) or on a test class (golden/property/structural
 * layers, where one class owns a whole set of IDs).
 *
 * <p>value() is an array so a single class can declare many IDs in one place: single
 * ID: @Requirement("FR-1.1"), many IDs: @Requirement({"FR-3.1", "FR-3.2", "FR-3.3"}) Java's
 * single-element-array shorthand keeps the single form valid.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Requirement {
  String[] value();
}
