package configswitcher.mesh;

import configswitcher.mesh.model.MeshOperationType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import configswitcher.mesh.service.MeshCaptureService;
import configswitcher.mesh.service.MeshOutputAnalyzer;
import configswitcher.mesh.ui.MeshRequestTableModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MeshOutputAnalyzerTest {

    private MeshCaptureService captureService;
    private MeshOutputAnalyzer analyzer;

    @BeforeEach
    public void setUp() {
        captureService = new MeshCaptureService(null);
        analyzer = captureService.getAnalyzer();
    }

    @Test
    public void testParseRealLogSequence() {
        String[] logLines = {
                "2026-09-27 01:06:26.848  INFO [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] n.n.f.b.g.GraphQlDataProviderEngine      : Execute GraphQL query: query GetUserInfoWithRoles {",
                "  ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles(",
                "    input: {userId: \"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}",
                "  ) {",
                "    userInfo {",
                "      id",
                "      firstName",
                "      lastName",
                "    }",
                "    roles {",
                "      id",
                "      name",
                "    }",
                "  }",
                "}",
                "2026-09-27 01:06:26.849 DEBUG [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] o.s.web.client.RestTemplate              : HTTP POST http://mesh-java.test.ecp/graphql",
                "2026-09-27 01:06:26.849 DEBUG [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] o.s.web.client.RestTemplate              : Writing [{variables={}, query=query GetUserInfoWithRoles {\n  ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles(\n    input: {userId: \"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}\n  ) {\n    userInfo {\n      id\n    }\n  }\n}}] as \"application/json\"",
                "2026-09-27 01:06:26.956 DEBUG [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] o.s.web.client.RestTemplate              : Response 200 OK",
                "2026-09-27 01:06:26.956  INFO [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] n.n.f.b.g.GraphQlDataProviderEngine      : Query result: {data={ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles={userInfo={id=eec9d22d-6124-4e97-9e88-17f2d2a956d3, personId=null, firstName=Тест, lastName=Тестов}, roles=[{id=2, name=user}]}}}"
        };

        for (String line : logLines) {
            analyzer.processLine(line);
        }

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size(), "Should have captured exactly 1 correlated request-response pair");

        MeshRequestEntry entry = entries.get(0);
        assertEquals("c1ff8fe5b6cd26a2", entry.getTraceId());
        assertEquals("c1ff8fe5b6cd26a2", entry.getSpanId());
        assertEquals("co", entry.getAppName());
        assertEquals("8024", entry.getPid());
        assertEquals("io-50906-exec-9", entry.getThreadName());

        assertEquals(MeshOperationType.QUERY, entry.getOperationType());
        assertEquals("GetUserInfoWithRoles", entry.getOperationName());
        assertEquals("ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles", entry.getRootField());
        assertEquals("http://mesh-java.test.ecp/graphql", entry.getEndpoint());
        assertEquals("200 OK", entry.getHttpStatus());
        assertEquals(MeshRequestStatus.SUCCESS, entry.getStatus());

        // Latency: 2026-09-27 01:06:26.848 to 2026-09-27 01:06:26.956 = 108ms
        assertEquals(108L, entry.getDurationMs());
        assertEquals("108 ms", entry.getFormattedDuration());

        // Time column format: time only (HH:mm:ss), no date part, no ms part
        assertEquals("01:06:26", entry.getFormattedTime());
        MeshRequestTableModel model = new MeshRequestTableModel();
        model.addEntry(entry);
        assertEquals("01:06:26", model.getValueAt(0, 1), "Table Time column must show HH:mm:ss without date or ms");

        // Check formatted response JSON
        String formattedJson = entry.getFormattedResponse();
        assertNotNull(formattedJson);
        assertTrue(formattedJson.contains("\"firstName\": \"Тест\""));
        assertTrue(formattedJson.contains("\"id\": 2"));

        // Check curl command generation
        String curl = captureService.generateCurlCommand(entry);
        assertTrue(curl.startsWith("curl -X POST 'http://mesh-java.test.ecp/graphql'"));
        assertTrue(curl.contains("X-B3-TraceId: c1ff8fe5b6cd26a2"));
        assertTrue(curl.contains("GetUserInfoWithRoles"));
    }

    @Test
    public void testMutationDetection() {
        String startLine = "2026-09-27 01:10:00.000  INFO [app,trace123,span456] 1234 --- [thread-1] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: mutation UpdateUser($id: ID!) { updateUser(id: $id) { id } }";
        String resultLine = "2026-09-27 01:10:00.050  INFO [app,trace123,span456] 1234 --- [thread-1] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {data={updateUser={id=999}}}";

        analyzer.processLine(startLine);
        analyzer.processLine(resultLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);
        assertEquals(MeshOperationType.MUTATION, entry.getOperationType());
        assertEquals("UpdateUser", entry.getOperationName());
        assertEquals(MeshRequestStatus.SUCCESS, entry.getStatus());
        assertEquals(50L, entry.getDurationMs());
    }

    @Test
    public void testErrorDetectionInResult() {
        String startLine = "2026-09-27 01:10:00.000  INFO [app,errtrace,errspan] 1234 --- [thread-err] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query Broken { brokenField }";
        String resultLine = "2026-09-27 01:10:00.030  INFO [app,errtrace,errspan] 1234 --- [thread-err] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {errors=[{message=Field not found, path=[brokenField]}]}";

        analyzer.processLine(startLine);
        analyzer.processLine(resultLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);
        assertEquals(MeshRequestStatus.ERROR, entry.getStatus());
        assertTrue(entry.getErrorMessage().contains("GraphQL response contains errors"));
    }

    @Test
    public void testHttpErrorDetection() {
        String startLine = "2026-09-27 01:10:00.000  INFO [app,httperr,span] 1234 --- [thread-http] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query BadReq { test }";
        String respLine = "2026-09-27 01:10:00.020 DEBUG [app,httperr,span] 1234 --- [thread-http] o.s.web.client.RestTemplate : Response 500 Internal Server Error";

        analyzer.processLine(startLine);
        analyzer.processLine(respLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);
        assertEquals("500 Internal Server Error", entry.getHttpStatus());
        assertEquals(MeshRequestStatus.ERROR, entry.getStatus());
    }

    @Test
    public void testStreamChunking() {
        // Test chunk processing with fragmented incoming data
        String chunk1 = "2026-09-27 01:10:00.000  INFO [app,tr1,sp1] 1 --- [t1] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query Q1 { id }\n2026-09-27 01:10";
        String chunk2 = ":00.080  INFO [app,tr1,sp1] 1 --- [t1] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {data={id=1}}\n";

        analyzer.processChunk(chunk1);
        analyzer.processChunk(chunk2);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        assertEquals(80L, entries.get(0).getDurationMs());
    }

    @Test
    public void testConcurrentInterleavedRequests() {
        // Thread A starts Query A
        analyzer.processLine("2026-09-27 01:10:00.000  INFO [co,traceA,spanA] 100 --- [thread-A] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query QueryA { a }");
        // Thread B starts Mutation B before Query A finishes
        analyzer.processLine("2026-09-27 01:10:00.010  INFO [co,traceB,spanB] 100 --- [thread-B] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: mutation MutationB { b }");
        // Thread A gets HTTP POST
        analyzer.processLine("2026-09-27 01:10:00.015 DEBUG [co,traceA,spanA] 100 --- [thread-A] o.s.web.client.RestTemplate : HTTP POST http://mesh-java.test.ecp/graphql");
        // Thread B finishes first!
        analyzer.processLine("2026-09-27 01:10:00.040  INFO [co,traceB,spanB] 100 --- [thread-B] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {data={b=true}}");
        // Thread A finishes later!
        analyzer.processLine("2026-09-27 01:10:00.070  INFO [co,traceA,spanA] 100 --- [thread-A] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {data={a=42}}");

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(2, entries.size());

        MeshRequestEntry entryA = entries.stream().filter(e -> "traceA".equals(e.getTraceId())).findFirst().orElse(null);
        MeshRequestEntry entryB = entries.stream().filter(e -> "traceB".equals(e.getTraceId())).findFirst().orElse(null);

        assertNotNull(entryA);
        assertNotNull(entryB);

        assertEquals("QueryA", entryA.getOperationName());
        assertEquals(70L, entryA.getDurationMs());
        assertTrue(entryA.getFormattedResponse().contains("\"a\": 42"));

        assertEquals("MutationB", entryB.getOperationName());
        assertEquals(30L, entryB.getDurationMs());
        assertTrue(entryB.getFormattedResponse().contains("\"b\": true"));
    }

    @Test
    public void testStandaloneWritingJsonWithVariables() {
        String reqLine = "2026-09-27 01:15:00.000 DEBUG [co,trV,spV] 100 --- [thV] o.s.web.client.RestTemplate : Writing [{variables={id=123, status=ACTIVE}, query=query GetItem { item(id: 123) { id } }}] as \"application/json\"";
        String respLine = "2026-09-27 01:15:00.055  INFO [co,trV,spV] 100 --- [thV] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {data={item={id=123}}}";

        analyzer.processLine(reqLine);
        analyzer.processLine(respLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);
        assertEquals("GetItem", entry.getOperationName());
        assertEquals(55L, entry.getDurationMs());
        assertNotNull(entry.getFormattedVariables());
        assertTrue(entry.getFormattedVariables().contains("\"id\": 123"));
        assertTrue(entry.getFormattedVariables().contains("\"status\": \"ACTIVE\""));
    }

    @Test
    public void testJsonLogFormatWithRealSnippet() {
        String[] jsonLogs = {
                // Non-GraphQL debug line from Spring PropertySource
                "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"module\":\"org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver$DefaultResolver\",\"service\":\"SERVICE_NAME_IS_UNDEFINED\",\"destination\":\"SERVICE_DESTINATION_IS_UNDEFINED\",\"level\":\"DEBUG\",\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"message\":\"Found key 'app.api.action.edit_list.type' in PropertySource 'appProperties' with value of type String\",\"context\":{\"businessContext\":null,\"tracingContext\":{\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"parentSpanId\":null},\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\",\"requestSource\":null,\"sourceProtocol\":null},\"txContext\":null,\"uiTaskContext\":null,\"userTaskContext\":null,\"customContexts\":{}},\"index\":\"ecp-null-debug\"}",
                // GraphQL Query Start
                "{\"timestamp\":\"2026-09-27T01:40:28.020+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"module\":\"com.example.graphql.GraphQlDataProviderEngine\",\"service\":\"my-service\",\"destination\":\"mesh\",\"level\":\"INFO\",\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"message\":\"Execute GraphQL query: query GetUserInfoWithRoles { ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles(input: {userId: \\\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\\\"}) { userInfo { id firstName } } }\",\"context\":{\"tracingContext\":{\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\"},\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}",
                // HTTP POST
                "{\"timestamp\":\"2026-09-27T01:40:28.022+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"module\":\"org.springframework.web.client.RestTemplate\",\"service\":\"my-service\",\"destination\":\"mesh\",\"level\":\"DEBUG\",\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"message\":\"HTTP POST https://mesh.domain.local/graphql\",\"context\":{\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}",
                // Writing JSON
                "{\"timestamp\":\"2026-09-27T01:40:28.025+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"module\":\"org.springframework.web.client.RestTemplate\",\"service\":\"my-service\",\"destination\":\"mesh\",\"level\":\"DEBUG\",\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"message\":\"Writing [{variables={userId=eec9d22d-6124-4e97-9e88-17f2d2a956d3}, query=query GetUserInfoWithRoles { ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles(input: {userId: \\\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\\\"}) { userInfo { id firstName } } }}] as \\\"application/json\\\"\",\"context\":{\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}",
                // Response 200 OK
                "{\"timestamp\":\"2026-09-27T01:40:28.160+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"module\":\"org.springframework.web.client.RestTemplate\",\"service\":\"my-service\",\"destination\":\"mesh\",\"level\":\"DEBUG\",\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"message\":\"Response 200 OK\",\"context\":{\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}",
                // Query result
                "{\"timestamp\":\"2026-09-27T01:40:28.165+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"module\":\"com.example.graphql.GraphQlDataProviderEngine\",\"service\":\"my-service\",\"destination\":\"mesh\",\"level\":\"INFO\",\"traceId\":\"25175ec75e149c43\",\"spanId\":\"25175ec75e149c43\",\"message\":\"Query result: {data={ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles={userInfo={id=eec9d22d-6124-4e97-9e88-17f2d2a956d3, firstName=Иван}}}}\",\"context\":{\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}",
                // Another non-GraphQL warning line
                "{\"timestamp\":\"2026-09-27T01:40:30.046+03:00\",\"threadName\":\"http-nio-50906-exec-9\",\"module\":\"com.example.cache.CacheTemplate\",\"service\":\"SERVICE_NAME_IS_UNDEFINED\",\"destination\":\"SERVICE_DESTINATION_IS_UNDEFINED\",\"level\":\"WARN\",\"traceId\":\"172fb7796cfd9ef7\",\"spanId\":\"172fb7796cfd9ef7\",\"message\":\"Cannot find cache named [app-cache.compiled] for CacheTemplate\",\"context\":{\"tracingContext\":{\"traceId\":\"172fb7796cfd9ef7\",\"spanId\":\"172fb7796cfd9ef7\"},\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}"
        };

        for (String line : jsonLogs) {
            analyzer.processLine(line);
        }

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size(), "Should capture exactly 1 request from JSON logs");

        MeshRequestEntry entry = entries.get(0);
        assertEquals("25175ec75e149c43", entry.getTraceId());
        assertEquals("25175ec75e149c43", entry.getSpanId());
        assertEquals("eec9d22d-6124-4e97-9e88-17f2d2a956d3", entry.getUserId());
        assertEquals("http-nio-50906-exec-8", entry.getThreadName());
        assertEquals("my-service", entry.getAppName());
        assertEquals("GetUserInfoWithRoles", entry.getOperationName());
        assertEquals(MeshOperationType.QUERY, entry.getOperationType());
        assertEquals("https://mesh.domain.local/graphql", entry.getEndpoint());
        assertEquals("200 OK", entry.getHttpStatus());
        assertEquals(MeshRequestStatus.SUCCESS, entry.getStatus());

        // 2026-09-27T01:40:28.020+03:00 to 2026-09-27T01:40:28.165+03:00 = 145 ms
        assertEquals(145L, entry.getDurationMs());
        assertEquals("145 ms", entry.getFormattedDuration());

        // Time column format: time only (HH:mm:ss), no date part, no ms part
        assertEquals("01:40:28", entry.getFormattedTime());
        MeshRequestTableModel jsonModel = new MeshRequestTableModel();
        jsonModel.addEntry(entry);
        assertEquals("01:40:28", jsonModel.getValueAt(0, 1), "Table Time column must show HH:mm:ss without date or ms");

        assertNotNull(entry.getFormattedResponse());
        assertTrue(entry.getFormattedResponse().contains("\"firstName\": \"Иван\""));
        assertTrue(entry.matchesSearch("eec9d22d-6124-4e97-9e88-17f2d2a956d3"));
    }

    @Test
    public void testJsonLogFormatWithNativeJsonMessage() {
        String req = "{\"timestamp\":\"2026-09-27T01:50:00.000Z\",\"threadName\":\"exec-1\",\"level\":\"DEBUG\",\"traceId\":\"traceJson1\",\"spanId\":\"spanJson1\",\"message\":\"{\\\"query\\\": \\\"mutation SaveData($id: ID!) { saveData(id: $id) { success } }\\\", \\\"variables\\\": {\\\"id\\\": 555}}\",\"context\":{\"authContext\":{\"userId\":\"usr-777\"}}}";
        String resp = "{\"timestamp\":\"2026-09-27T01:50:00.050Z\",\"threadName\":\"exec-1\",\"level\":\"INFO\",\"traceId\":\"traceJson1\",\"spanId\":\"spanJson1\",\"message\":\"Query result: {data={saveData={success=true}}}\"}";

        analyzer.processLine(req);
        analyzer.processLine(resp);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertEquals("SaveData", entry.getOperationName());
        assertEquals(MeshOperationType.MUTATION, entry.getOperationType());
        assertEquals("traceJson1", entry.getTraceId());
        assertEquals("usr-777", entry.getUserId());
        assertEquals(50L, entry.getDurationMs());
        assertNotNull(entry.getFormattedVariables());
        assertTrue(entry.getFormattedVariables().contains("555"));
    }

    @Test
    public void testDebugRestTemplateLogRecords() {
        String req = "{\"timestamp\":\"2026-10-07T12:20:01.845+03:00\",\"threadName\":\"http-nio-50906-exec-4\",\"module\":\"org.springframework.web.client.RestTemplate\",\"service\":\"SERVICE_NAME_IS_UNDEFINED\",\"destination\":\"SERVICE_DESTINATION_IS_UNDEFINED\",\"level\":\"DEBUG\",\"traceId\":\"cb46cb8f5f7632a6\",\"spanId\":\"cb46cb8f5f7632a6\",\"message\":\"Writing [{variables={}, query=\\n            \\n                query InsurantSearch {\\n                  RV_findInRsInsurerReadView(\\n                    pageable: {page: 0, size: 10}\\n                    criteriaGroup:\\n                    {\\n                      operator: AND,\\n                      criteriaGroups:\\n                      [\\n                        {\\n                          operator: AND,\\n                          criteriaGroups:\\n                          [\\n                            {\\n            operator: AND,\\n            criteriaSet: { field: insurer_singleRegNumber, operation: EQUALS, value: \\\"1000904167\\\" }\\n            }\\n                          ]\\n                        }\\n                      ]\\n                    }\\n                  ) {\\n                    data {\\n                      id\\n                      singleRegNumber\\n                    }\\n                    total_count\\n                  }\\n                }\\n            \\n        }] as \\\"application/json\\\"\",\"context\":{\"tracingContext\":{\"traceId\":\"cb46cb8f5f7632a6\",\"spanId\":\"cb46cb8f5f7632a6\"},\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}";
        String resp = "{\"timestamp\":\"2026-10-07T12:20:02.103+03:00\",\"threadName\":\"http-nio-50906-exec-4\",\"module\":\"org.springframework.web.client.RestTemplate\",\"service\":\"SERVICE_NAME_IS_UNDEFINED\",\"destination\":\"SERVICE_DESTINATION_IS_UNDEFINED\",\"level\":\"DEBUG\",\"traceId\":\"cb46cb8f5f7632a6\",\"spanId\":\"cb46cb8f5f7632a6\",\"message\":\"Response 200 OK\",\"context\":{\"tracingContext\":{\"traceId\":\"cb46cb8f5f7632a6\",\"spanId\":\"cb46cb8f5f7632a6\"},\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\"}}}";

        analyzer.processLine(req);
        analyzer.processLine(resp);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size(), "Should capture exactly 1 request");
        MeshRequestEntry entry = entries.get(0);

        System.out.println("Entry log level: " + entry.getLogLevel());
        System.out.println("Entry status: " + entry.getStatus());
        System.out.println("Entry duration: " + entry.getDurationMs());
        System.out.println("Entry operation: " + entry.getOperationName());

        assertEquals("DEBUG", entry.getLogLevel(), "Entry log level should be DEBUG");
        assertEquals("InsurantSearch", entry.getOperationName());
        assertEquals("200 OK", entry.getHttpStatus());
        assertEquals(MeshRequestStatus.SUCCESS, entry.getStatus());
        assertEquals(258L, entry.getDurationMs());

        // Verify table model reflects Level column and DEBUG filter
        MeshRequestTableModel model = new MeshRequestTableModel();
        model.addEntry(entry);
        assertEquals(7, model.getColumnCount(), "Table must have 7 columns");
        assertEquals("Level", model.getColumnName(2), "Column 2 should be Level");
        assertEquals("DEBUG", model.getValueAt(0, 2), "Row 0 Column 2 should be DEBUG");

        model.setLevelFilter("DEBUG");
        assertEquals(1, model.getRowCount(), "DEBUG filter must match DEBUG entry");

        model.setLevelFilter("INFO");
        assertEquals(0, model.getRowCount(), "INFO filter must not match DEBUG entry");

        model.setLevelFilter("All Levels");
        assertEquals(1, model.getRowCount(), "All Levels filter must match DEBUG entry");
    }
}
