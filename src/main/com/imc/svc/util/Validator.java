package svc.util;

public class Validator {
  public static long requirePositive(long v, String field) {
    if (v < 1l)
      throw new IllegalArgumentException(
          "Parameter " + field + " must be a non-zero positive number");
    return v;
  }
}
