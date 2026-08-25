package io.github.giovanicaprison.matching.benchmarks;

/**
 * One thing about the machine or the runtime, as found.
 *
 * <p>A setting that was asked for and did not take is worse than one nobody asked for, because the
 * run looks controlled. So a probe records what it wanted alongside what it got, and a run says
 * which of the two it is.
 *
 * @param name what the setting is called in the write up
 * @param source where it was read from, so a reader can check it by hand
 * @param expected what a measurement run needs, or null when the value is recorded rather than
 *     required
 * @param actual what was there
 * @param status whether the two agree
 */
public record Setting(
    String name, String source, String expected, String actual, Setting.Status status) {

  public enum Status {
    /** Either it matches what was asked for, or nothing was asked for. */
    OK,
    /** It was asked for and the machine says otherwise. */
    WRONG,
    /** Nothing to read. Normally a kernel that does not expose it, or the wrong platform. */
    UNAVAILABLE
  }

  static Setting recorded(final String name, final String source, final String actual) {
    return actual == null
        ? new Setting(name, source, null, null, Status.UNAVAILABLE)
        : new Setting(name, source, null, actual, Status.OK);
  }

  static Setting required(
      final String name, final String source, final String expected, final String actual) {
    if (actual == null) {
      return new Setting(name, source, expected, null, Status.UNAVAILABLE);
    }
    return new Setting(
        name, source, expected, actual, expected.equals(actual) ? Status.OK : Status.WRONG);
  }

  /** A run is only measurement grade when every required setting is what it asked for. */
  public boolean satisfied() {
    return expected == null || status == Status.OK;
  }

  /** Written one way wherever a setting lands in an artifact, expected null where none was. */
  void writeTo(final Json json) {
    json.object()
        .field("name", name)
        .field("source", source)
        .field("expected", expected)
        .field("actual", actual)
        .field("status", status.name())
        .end();
  }
}
