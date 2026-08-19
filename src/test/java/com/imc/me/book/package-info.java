/**
 * Tests for the order book and the structures it owns.
 *
 * <p>They sit in {@code com.imc.me.book} rather than in a package of their own because the entity
 * mutators and intrusive list links they need to assert on are package-private (OOD-1, OOD-4). Only
 * a caller in this package can name them.
 */
package com.imc.me.book;
