package eu.wohlben.qits.cli.access.publish;

import java.util.Map;

/**
 * The environment, as a value.
 *
 * <p>A wrapper this thin exists for one reason: the suite drives the whole program with a map, so
 * every "which variable was missing" message is a test rather than something discovered in a step
 * container. Nothing below {@link Cli} calls {@code System.getenv}.
 *
 * <p><b>Empty is absent.</b> qits-ci spells "off" as empty-never-absent for several of the variables
 * that reach a step container — the buildkit kill switch and the maven mirror both do — so a program
 * that distinguished the two would branch on a difference the platform does not make.
 */
record Env(Map<String, String> values) {

  static Env ofSystem() {
    return new Env(System.getenv());
  }

  /** The value, or {@code null} when it is unset or empty. */
  String get(String name) {
    String value = values.get(name);
    return value == null || value.isEmpty() ? null : value;
  }
}
