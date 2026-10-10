package eu.wohlben.qits.cli.access.changelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommitSubjectsTest {

    @Test
    void aScopeOfIdsNamesThemInOrderOnce() {
        assertThat(CommitSubjects.ids("feat(qits-893): x")).containsExactly("qits-893");
        assertThat(CommitSubjects.ids("fix(qits-10, qits-9,qits-10)!: x")).containsExactly("qits-10", "qits-9");
        assertThat(CommitSubjects.ids("(qits-1): no type")).containsExactly("qits-1");
        assertThat(CommitSubjects.ids("feat(my-proj-2-12): x")).containsExactly("my-proj-2-12");
    }

    @Test
    void anythingElseNamesNothing() {
        assertThat(CommitSubjects.ids("chore(deps): x")).isEmpty();
        assertThat(CommitSubjects.ids("feat(qits-1, deps): one part is not an id")).isEmpty();
        assertThat(CommitSubjects.ids("feat(qits-1) missing colon")).isEmpty();
        assertThat(CommitSubjects.ids("feat (qits-1): space in the type")).isEmpty();
        assertThat(CommitSubjects.ids("Release request rr: Ticket qits-1: x")).isEmpty();
        assertThat(CommitSubjects.ids("feat(-1): x")).isEmpty();
        assertThat(CommitSubjects.ids("feat(qits-1234567890123456789): nineteen digits")).isEmpty();
        assertThat(CommitSubjects.ids((String) null)).isEmpty();
    }

    @Test
    void theSubjectIsTheFirstLine() {
        assertThat(CommitSubjects.subject("feat(qits-1): x\r\n\nfix(qits-2): y")).isEqualTo("feat(qits-1): x");
        assertThat(CommitSubjects.subject(null)).isEmpty();
    }

    @Test
    void idsSortBySlugThenNumber() {
        assertThat(CommitSubjects.ids(List.of("fix(qits-10): a", "feat(qits-9, abc-100): b", "x(qits-9): c")))
                .containsExactly("abc-100", "qits-9", "qits-10");
        assertThat(CommitSubjects.slug("my-proj-12")).isEqualTo("my-proj");
        assertThatThrownBy(() -> CommitSubjects.slug("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
