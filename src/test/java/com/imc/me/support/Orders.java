package com.imc.me.support;

/**
 * Test-data builders that centralise construction here keep every test reading like prose ("a
 * resting sell at 100 for 10") instead of repeating positional constructor args, and means a later
 * signature change touches one file instead of hundreds of call sites.
 */
public final class Orders {
  private Orders() {}
  // TODO (Step 1): add builders returning the engine's NewOrder/AmendOrder types.
}
