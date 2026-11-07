package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.report.contracts.RecordedOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link GoldenMasterMatch} (qits-1940): when a recorded call covers an operation of the document. */
class GoldenMasterMatchTest {

    private static final List<String> NO_PREFIX = List.of("");

    @Test
    void theSameOperationIdCoversWhateverThePath() {
        ApiOperation operation = new ApiOperation("getWork", "GET", "/projects/api/work/{id}");

        assertThat(GoldenMasterMatch.covers(new RecordedOperation("getWork", "POST", "/elsewhere"), operation,
                NO_PREFIX)).isTrue();
        assertThat(GoldenMasterMatch.covers(new RecordedOperation("listWork", "POST", "/elsewhere"), operation,
                NO_PREFIX)).isFalse();
    }

    @Test
    void aNullOperationIdNeverMatchesAnotherNull() {
        ApiOperation operation = new ApiOperation(null, "GET", "/a");

        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, "GET", "/b"), operation, NO_PREFIX)).isFalse();
    }

    @Test
    void aTemplateSegmentMatchesOneNonEmptySegment() {
        ApiOperation operation = new ApiOperation(null, "GET", "/work/{id}/comments");

        assertThat(covers("GET", "/work/qits-9/comments", operation)).isTrue();
        assertThat(covers("GET", "/work/{qualifiedId}/comments", operation)).as("a recorded template").isTrue();
        assertThat(covers("GET", "/work//comments", operation)).as("an empty segment").isFalse();
        assertThat(covers("GET", "/work/a/b/comments", operation)).as("two segments").isFalse();
        assertThat(covers("GET", "/work/qits-9", operation)).isFalse();
    }

    @Test
    void literalSegmentsCompareLiterally() {
        ApiOperation literal = new ApiOperation(null, "GET", "/work/mine");

        assertThat(covers("GET", "/work/mine", literal)).isTrue();
        assertThat(covers("GET", "/work/qits-9", literal)).isFalse();
        assertThat(covers("GET", "/work/{id}", literal)).as("a recorded template is literal").isFalse();
        assertThat(covers("GET", "/Work/mine", literal)).isFalse();
        assertThat(covers("GET", "/work.mine", new ApiOperation(null, "GET", "/work.mine"))).isTrue();
        assertThat(covers("GET", "/workXmine", new ApiOperation(null, "GET", "/work.mine")))
                .as("a dot is no wildcard").isFalse();
    }

    @Test
    void aTemplateMayTakeUpPartOfASegment() {
        ApiOperation operation = new ApiOperation(null, "GET", "/files/{name}.json");

        assertThat(covers("GET", "/files/report.json", operation)).isTrue();
        assertThat(covers("GET", "/files/report.xml", operation)).isFalse();
    }

    @Test
    void theMethodComparesInAnyCase() {
        ApiOperation operation = new ApiOperation(null, "POST", "/work");

        assertThat(covers("post", "/work", operation)).isTrue();
        assertThat(covers("GET", "/work", operation)).isFalse();
        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, null, "/work"), operation, NO_PREFIX)).isFalse();
    }

    @Test
    void theQueryIsLeftOff() {
        ApiOperation operation = new ApiOperation(null, "GET", "/work");

        assertThat(covers("GET", "/work?project=qits&state=all", operation)).isTrue();
        assertThat(covers("GET", "/work/?project=qits", operation)).isTrue();
    }

    @Test
    void oneTrailingSlashIsIgnoredOnEitherSide() {
        assertThat(covers("GET", "/work/", new ApiOperation(null, "GET", "/work"))).isTrue();
        assertThat(covers("GET", "/work", new ApiOperation(null, "GET", "/work/"))).isTrue();
        assertThat(covers("GET", "/", new ApiOperation(null, "GET", "/"))).isTrue();
        assertThat(covers("GET", "/work", new ApiOperation(null, "GET", "/"))).isFalse();
    }

    @Test
    void aServerPrefixWithItsOwnTemplateGoesInFront() {
        List<String> servers = List.of("", "/workspaces/container/{workspaceId}");
        ApiOperation operation = new ApiOperation(null, "GET", "/commands/{commandId}");

        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, "GET",
                "/workspaces/container/abc/commands/xyz"), operation, servers)).isTrue();
        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, "GET", "/commands/xyz"), operation, servers))
                .as("the server at /").isTrue();
        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, "GET",
                "/workspaces/container/abc/def/commands/xyz"), operation, servers)).isFalse();
    }

    @Test
    void onlyTheDocumentsServersArePrefixes() {
        ApiOperation operation = new ApiOperation(null, "GET", "/work");

        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, "GET", "/work"), operation, List.of("/api")))
                .isFalse();
        assertThat(GoldenMasterMatch.covers(new RecordedOperation(null, "GET", "/api/work"), operation,
                List.of("/api"))).isTrue();
    }

    @Test
    void uncoveredKeepsDocumentOrderAndNamesByOperationIdElseMethodAndPath() {
        OpenApiDocument document = new OpenApiDocument(NO_PREFIX, List.of(
                new ApiOperation("listWork", "GET", "/work"),
                new ApiOperation(null, "DELETE", "/work/{id}"),
                new ApiOperation("getWork", "GET", "/work/{id}"),
                new ApiOperation(null, "PUT", "/work/{id}")));
        List<RecordedOperation> recorded = List.of(new RecordedOperation("getWork", "GET", "/work/qits-9"),
                new RecordedOperation(null, "PUT", "/work/qits-9"));

        List<ApiOperation> uncovered = GoldenMasterMatch.uncovered(document, recorded);

        assertThat(uncovered).extracting(ApiOperation::name).containsExactly("listWork", "DELETE /work/{id}");
    }

    private static boolean covers(String method, String path, ApiOperation operation) {
        return GoldenMasterMatch.covers(new RecordedOperation(null, method, path), operation, NO_PREFIX);
    }
}
