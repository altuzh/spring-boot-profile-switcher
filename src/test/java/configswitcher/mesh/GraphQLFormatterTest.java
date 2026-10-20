package configswitcher.mesh;

import configswitcher.mesh.model.MeshOperationType;
import configswitcher.mesh.service.GraphQLFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GraphQLFormatterTest {

    @Test
    public void testFormatSingleLineQuery() {
        String compressed = "query GetUserInfoWithRoles { ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles(input: {userId: \"123\"}) { userInfo { id firstName } roles { id name } } }";
        String formatted = GraphQLFormatter.format(compressed);

        assertTrue(formatted.contains("{\n"));
        assertTrue(formatted.contains("  ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles"));
        assertTrue(formatted.contains("    userInfo {\n"));
        assertTrue(formatted.contains("      id"));
    }

    @Test
    public void testExtractOperationName() {
        assertEquals("GetUserInfoWithRoles",
                GraphQLFormatter.extractOperationName("query GetUserInfoWithRoles { test }"));
        assertEquals("UpdateClaimStatus",
                GraphQLFormatter.extractOperationName("mutation UpdateClaimStatus($id: String) { update(id: $id) }"));
        assertEquals("OnMessage",
                GraphQLFormatter.extractOperationName("subscription OnMessage { msg }"));
        assertNull(GraphQLFormatter.extractOperationName("{ test }"));
    }

    @Test
    public void testExtractRootField() {
        String query = "query GetUserInfoWithRoles {\n" +
                "  ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles(\n" +
                "    input: {userId: \"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}\n" +
                "  ) {\n" +
                "    userInfo { id }\n" +
                "  }\n" +
                "}";
        assertEquals("ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles", GraphQLFormatter.extractRootField(query));

        String anonymous = "{ viewer { name } }";
        assertEquals("viewer", GraphQLFormatter.extractRootField(anonymous));
    }

    @Test
    public void testMeshOperationTypeDetection() {
        assertEquals(MeshOperationType.QUERY, MeshOperationType.fromQueryText("query GetInfo { id }"));
        assertEquals(MeshOperationType.MUTATION, MeshOperationType.fromQueryText("mutation UpdateInfo { id }"));
        assertEquals(MeshOperationType.QUERY, MeshOperationType.fromQueryText("{ id name }"));
        assertEquals(MeshOperationType.MUTATION, MeshOperationType.fromToken("mutation"));
        assertEquals(MeshOperationType.QUERY, MeshOperationType.fromToken("query"));
    }
}
