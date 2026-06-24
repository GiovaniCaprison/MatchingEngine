package svc.core;

import java.util.HashMap;
import java.util.Map;

public class OrderIdentifierService {
  private static Map<Long, Long> internalToExternalOrderMap = new HashMap<>();
  private static long internalUid = 0;
  // right now obviously this is not very useful
  // but in time as I turn this into a real exchange
  // or rather I build out the project
  // this will serve a real and important purpose
  private static long externalId = 0;

  public static long internalId() {
    return ++internalUid;
  }

  public static long externalId(long internalId) {
    return internalToExternalOrderMap.getOrDefault(internalId, ++externalId);
  }
}
