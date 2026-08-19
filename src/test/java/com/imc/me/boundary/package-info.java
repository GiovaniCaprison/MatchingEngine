/**
 * Tests that drive the engine through nothing but its public API.
 *
 * <p>They sit outside {@code com.imc.me} so that the compiler, rather than discipline, keeps them
 * from reaching past the boundary. A test that cannot see an internal cannot accidentally depend on
 * one, which is what makes these the tests a rewrite has to keep passing.
 */
package com.imc.me.boundary;
