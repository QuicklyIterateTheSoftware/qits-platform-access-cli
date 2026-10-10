package eu.wohlben.qits.cli.access.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.core.model.matchingrules.MatchingRuleGroup;
import au.com.dius.pact.core.model.matchingrules.MinMaxTypeMatcher;
import au.com.dius.pact.core.model.matchingrules.MinTypeMatcher;
import au.com.dius.pact.core.model.matchingrules.RuleLogic;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>How {@link GoldenMasters} turns a recorded array into a pact matcher</b> (qits-965): one
 * template where every element carries the same keys, element by element where they do not —
 * {@code listProjectWork}'s epics and tickets carry {@code blocked}, its features and tasks do not,
 * and a merged template would demand it of all of them.
 *
 * <p>Rule paths are as the DSL holds them, below the body root: {@code .entities[*].id} is the
 * pact's {@code $.entities[*].id}.
 */
class GoldenMastersArrayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final GoldenMasters.Operation OP = new GoldenMasters.Operation(
      "a state", Map.of(), "listProjectWork", "GET", "/work", null, 200, "unused.json",
      Set.of(".entities[*].id"), Set.of(), Set.of(), Set.of(), null);

  private static final String ID_1 = "00000000-0000-4000-8000-000000000001";
  private static final String ID_2 = "00000000-0000-4000-8000-000000000002";

  @Test
  void homogeneousElementsKeepOneTemplateOfExactLength() throws Exception {
    DslPart part = GoldenMasters.responseBody(OP, MAPPER.readTree("""
        {"entities": [
          {"id": "%s", "archetype": "EPIC", "blocked": false, "parent": null},
          {"id": "%s", "archetype": "TICKET", "blocked": true, "parent": "%s"}
        ]}""".formatted(ID_1, ID_2, ID_1)));
    Map<String, MatchingRuleGroup> rules = part.getMatchers().getMatchingRules();

    assertThat(rules.get(".entities").getRules()).containsExactly(new MinMaxTypeMatcher(2, 2));
    assertThat(rules.get(".entities[*].blocked").getRules()).containsExactly(TypeMatcher.INSTANCE);
    assertThat(rules.get(".entities[*].archetype").getRules()).containsExactly(TypeMatcher.INSTANCE);
    // null in one element, a value in another: type OR null, as before.
    assertThat(rules.get(".entities[*].parent").getRuleLogic()).isEqualTo(RuleLogic.OR);
    assertThat(rules.get(".entities[*].id").getRules()).hasSize(1);
  }

  @Test
  void elementsDisagreeingOnTheirKeysAreMatchedElementByElement() throws Exception {
    DslPart part = GoldenMasters.responseBody(OP, MAPPER.readTree("""
        {"entities": [
          {"id": "%s", "archetype": "EPIC", "blocked": false, "parent": null},
          {"id": "%s", "archetype": "FEATURE", "parent": "%s"}
        ]}""".formatted(ID_1, ID_2, ID_1)));
    Map<String, MatchingRuleGroup> rules = part.getMatchers().getMatchingRules();
    JsonNode body = MAPPER.readTree(part.getBody().serialise());

    // No template demanding `blocked` of every element, and no length-free "each like".
    assertThat(rules).doesNotContainKeys(".entities", ".entities[*].blocked");
    // Each element against its own recording: the epic's `blocked` checked by type, the feature
    // expected without it, its `parent` a UUID and the epic's an exact null.
    assertThat(body.path("entities")).hasSize(2);
    assertThat(body.path("entities").get(0).has("blocked")).isTrue();
    assertThat(body.path("entities").get(1).has("blocked")).isFalse();
    assertThat(rules.get(".entities[0].blocked").getRules()).containsExactly(TypeMatcher.INSTANCE);
    assertThat(rules.get(".entities[1].archetype").getRules()).containsExactly(TypeMatcher.INSTANCE);
    assertThat(rules).doesNotContainKeys(".entities[1].blocked", ".entities[0].parent");
    assertThat(body.path("entities").get(0).get("parent").isNull()).isTrue();
    assertThat(rules.get(".entities[0].id").getRules()).hasSize(1);
    assertThat(rules.get(".entities[1].parent").getRules()).hasSize(1);
  }

  @Test
  void aNestedObjectWhoseKeysDisagreeAlsoFallsBackToElementByElement() throws Exception {
    DslPart part = GoldenMasters.responseBody(OP, MAPPER.readTree("""
        {"entities": [
          {"id": "%s", "meta": {"a": 1, "b": 2}},
          {"id": "%s", "meta": {"a": 1}}
        ]}""".formatted(ID_1, ID_2)));
    Map<String, MatchingRuleGroup> rules = part.getMatchers().getMatchingRules();

    assertThat(rules).doesNotContainKeys(".entities", ".entities[*].meta.b");
    assertThat(rules).containsKeys(".entities[0].meta.b", ".entities[1].meta.a");
  }
  @Test
  void aNestedArrayRecordedAtDifferentLengthsHasAMinimumAndNoMaximum() throws Exception {
    // listReleaseRequestCommits (qits-893): the fold's merge commit has three parents and no files,
    // every other commit one of each. No one exact length holds for every commit.
    DslPart part = GoldenMasters.responseBody(OP, MAPPER.readTree("""
        {"commits": [
          {"hash": "m", "files": [], "parents": ["a", "b", "c"]},
          {"hash": "c", "files": ["src/c.txt"], "parents": ["a"]},
          {"hash": "b", "files": ["README.md"], "parents": ["a"]}
        ]}"""));
    Map<String, MatchingRuleGroup> rules = part.getMatchers().getMatchingRules();
    JsonNode body = MAPPER.readTree(part.getBody().serialise());

    // The outer array's length agrees with itself: still exact.
    assertThat(rules.get(".commits").getRules()).containsExactly(new MinMaxTypeMatcher(3, 3));
    assertThat(rules.get(".commits[*].parents").getRules()).containsExactly(new MinTypeMatcher(1));
    assertThat(rules.get(".commits[*].files").getRules()).containsExactly(new MinTypeMatcher(0));
    assertThat(rules.get(".commits[*].parents[*]").getRules()).containsExactly(TypeMatcher.INSTANCE);
    assertThat(rules.get(".commits[*].files[*]").getRules()).containsExactly(TypeMatcher.INSTANCE);
    // An empty first recording still gets an element to match by: borrowed from a sibling.
    assertThat(body.path("commits").get(0).path("files").get(0).asText()).isEqualTo("src/c.txt");
  }
}
