package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ecosystem registry: one implementation per package type, found by its type. */
class ContentHashRegistryTest {

  @Test
  void everyTypeIsUnique() {
    List<String> types = ContentHash.ALL.stream().map(ContentHash::type).toList();
    assertEquals(types.size(), new HashSet<>(types).size(), types.toString());
  }

  @Test
  void eachTypeResolvesToItsOwnImplementation() {
    for (ContentHash hash : ContentHash.ALL) {
      assertSame(hash, ContentHash.forType(hash.type()));
    }
  }

  @Test
  void anUnknownTypeIsRefused() {
    assertThrows(CliException.class, () -> ContentHash.forType("cargo"));
  }
}
