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

public class MeshDeduplicationTest {

    private MeshCaptureService captureService;
    private MeshOutputAnalyzer analyzer;
    private MeshRequestTableModel tableModel;

    @BeforeEach
    public void setUp() {
        captureService = new MeshCaptureService(null);
        analyzer = captureService.getAnalyzer();
        tableModel = new MeshRequestTableModel();
        captureService.addListener(new MeshCaptureService.MeshCaptureListener() {
            @Override
            public void onEntryAdded(MeshRequestEntry entry) {
                tableModel.addEntry(entry);
            }

            @Override
            public void onEntryUpdated(MeshRequestEntry entry) {
                tableModel.updateEntry(entry);
            }

            @Override
            public void onCleared() {
                tableModel.clear();
            }
        });
    }

    @Test
    public void testMeshCaptureServiceDeduplicationByTimestamp() {
        String timestamp = "2026-09-27 20:42:37.848";
        long epoch = 1790530957848L;

        MeshRequestEntry entry1 = new MeshRequestEntry(1, timestamp, epoch);
        entry1.setOperationName("GetUserInfo");
        entry1.setRawQuery("query GetUserInfo { user { id } }");
        entry1.setStatus(MeshRequestStatus.PENDING);

        captureService.onEntryCreated(entry1);
        assertEquals(1, captureService.getEntries().size());
        assertEquals(1, tableModel.getRowCount());

        // Attempt to create a duplicate entry with the same timestamp (e.g. from session.log re-scan)
        MeshRequestEntry entry2 = new MeshRequestEntry(2, timestamp, epoch);
        entry2.setOperationName("GetUserInfo");
        entry2.setStatus(MeshRequestStatus.SUCCESS);
        entry2.setHttpStatus("200 OK");
        entry2.setDurationMs(787L);

        captureService.onEntryCreated(entry2);

        // Crucial: entries size must still be 1 (NO duplicate rows)
        assertEquals(1, captureService.getEntries().size(), "Should not create duplicate entry with same timestamp");
        assertEquals(1, tableModel.getRowCount(), "Table model should not have duplicate row");

        // The existing entry should be merged/updated with the new status and duration
        MeshRequestEntry merged = captureService.getEntries().get(0);
        assertEquals(MeshRequestStatus.SUCCESS, merged.getStatus());
        assertEquals("200 OK", merged.getHttpStatus());
        assertEquals(787L, merged.getDurationMs());
    }

    @Test
    public void testLogRescanDoesNotDuplicateEntries() {
        String[] logBatch = {
                "2026-09-27 20:42:37.848  INFO [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] n.n.f.b.g.GraphQlDataProviderEngine      : Execute GraphQL query: query GetUserInfo { user { id } }",
                "2026-09-27 20:42:37.849 DEBUG [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] o.s.web.client.RestTemplate              : HTTP POST http://mesh-java.test.ecp/graphql",
                "2026-09-27 20:42:37.956 DEBUG [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] o.s.web.client.RestTemplate              : Response 200 OK",
                "2026-09-27 20:42:37.956  INFO [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] n.n.f.b.g.GraphQlDataProviderEngine      : Query result: {data={user={id=\"123\"}}}"
        };

        // First pass: live application run
        for (String line : logBatch) {
            analyzer.processLine(line);
        }

        assertEquals(1, captureService.getEntries().size());
        assertEquals(1, tableModel.getRowCount());
        MeshRequestEntry initial = captureService.getEntries().get(0);
        assertEquals(MeshRequestStatus.SUCCESS, initial.getStatus());

        // Second pass: user clicks "Scan session.log", which replays the exact same lines from the log file
        for (String line : logBatch) {
            analyzer.processLine(line);
        }

        assertEquals(1, captureService.getEntries().size(), "Re-scanning session.log must not duplicate records");
        assertEquals(1, tableModel.getRowCount(), "Table model must not duplicate rows");
    }

    @Test
    public void testDifferentTimestampsArePreserved() {
        String[] logBatch = {
                // Request 1 at 20:42:37.100
                "2026-09-27 20:42:37.100  INFO [co,trace1,span1] 8024 --- [exec-1] n.n.f.b.g.GraphQlDataProviderEngine      : Execute GraphQL query: query Query1 { a }",
                "2026-09-27 20:42:37.200  INFO [co,trace1,span1] 8024 --- [exec-1] n.n.f.b.g.GraphQlDataProviderEngine      : Query result: {data={a=1}}",
                // Request 2 at 20:42:38.200
                "2026-09-27 20:42:38.200  INFO [co,trace2,span2] 8024 --- [exec-2] n.n.f.b.g.GraphQlDataProviderEngine      : Execute GraphQL query: query Query2 { b }",
                "2026-09-27 20:42:38.300  INFO [co,trace2,span2] 8024 --- [exec-2] n.n.f.b.g.GraphQlDataProviderEngine      : Query result: {data={b=2}}"
        };

        for (String line : logBatch) {
            analyzer.processLine(line);
        }

        assertEquals(2, captureService.getEntries().size());
        assertEquals(2, tableModel.getRowCount());
        assertEquals("Query1", captureService.getEntries().get(0).getOperationName());
        assertEquals("Query2", captureService.getEntries().get(1).getOperationName());
    }

    @Test
    public void testTableModelSetEntriesDeduplicates() {
        MeshRequestEntry e1 = new MeshRequestEntry(1, "2026-09-27 20:42:37.848", 1000L);
        MeshRequestEntry e2 = new MeshRequestEntry(2, "2026-09-27 20:42:37.848", 1000L); // Duplicate timestamp
        MeshRequestEntry e3 = new MeshRequestEntry(3, "2026-09-27 20:42:38.000", 2000L);

        tableModel.setEntries(List.of(e1, e2, e3));

        assertEquals(2, tableModel.getRowCount(), "setEntries should discard entries with duplicate timestamps");
        assertEquals(1, tableModel.getEntryAt(0).getId());
        assertEquals(3, tableModel.getEntryAt(1).getId());
    }

    @Test
    public void testBuildMeshBrowserUrlWithQueryParams() {
        MeshRequestEntry entry = new MeshRequestEntry(1, "2026-09-27 20:42:37.848", 1000L);
        entry.setOperationName("MyQuery");
        entry.setFormattedQuery("query MyQuery {\n  user {\n    id\n  }\n}");
        entry.setFormattedVariables("{\"userId\": \"123\"}");

        String base = "https://mesh.dev.ecp/graphql";
        String browserUrl = configswitcher.mesh.ui.MeshDetailPanel.buildMeshBrowserUrl(base, entry);

        assertTrue(browserUrl.startsWith("https://mesh.dev.ecp/graphql?query="));
        assertTrue(browserUrl.contains("query+MyQuery"));
        assertTrue(browserUrl.contains("&variables="));
        assertTrue(browserUrl.contains("userId"));
        // GraphiQL web console does not use &operationName=
        assertFalse(browserUrl.contains("&operationName="));

        // If entry is null, returns base URL
        assertEquals("https://mesh.dev.ecp/graphql", configswitcher.mesh.ui.MeshDetailPanel.buildMeshBrowserUrl(base, null));
    }

    @Test
    public void testUserExampleGraphiqlUrlEncoding() {
        String base = "https://ui.mesh-java.test.ecp/graphiql/";
        String query = "                    query MyQuery {\n" +
                "                    ruGovPfrEcpFoPrintedformGrpcClient_PrintedFormService_GetPrintedFormByTaskId(\n" +
                "                    input: {id: {value: \"2b1204d2-1446-b548-6352-a3e8036cc1ae\"}}\n" +
                "                    ) {\n" +
                "                    info {\n" +
                "                    data\n" +
                "                    errorMessage\n" +
                "                    result\n" +
                "                    resultCode\n" +
                "                    resultMesage\n" +
                "                    }\n" +
                "                    response {\n" +
                "                    docInfoList {\n" +
                "                    data {\n" +
                "                    value\n" +
                "                    }\n" +
                "                    docType\n" +
                "                    formFormat\n" +
                "                    id {\n" +
                "                    value\n" +
                "                    }\n" +
                "                    }\n" +
                "                    request {\n" +
                "                    dateStart {\n" +
                "                    value\n" +
                "                    }\n" +
                "                    }\n" +
                "                    }\n" +
                "                    }";

        MeshRequestEntry entry = new MeshRequestEntry(1, "2026-09-27 20:42:37.848", 1000L);
        entry.setRawQuery(query);
        entry.setFormattedQuery(query);
        entry.setOperationName("MyQuery");

        String resultUrl = configswitcher.mesh.ui.MeshDetailPanel.buildMeshBrowserUrl(base, entry);

        // Verify base URL and query parameter structure
        assertTrue(resultUrl.startsWith("https://ui.mesh-java.test.ecp/graphiql/?query="));
        assertTrue(resultUrl.contains("++++++++++++++++++++query+MyQuery+%7B%0A"));
        assertTrue(resultUrl.contains("ruGovPfrEcpFoPrintedformGrpcClient_PrintedFormService_GetPrintedFormByTaskId%28"));
        assertTrue(resultUrl.contains("input%3A+%7Bid%3A+%7Bvalue%3A+%222b1204d2-1446-b548-6352-a3e8036cc1ae%22%7D%7D"));
        // Ensure no redundant operationName or variables when empty
        assertFalse(resultUrl.contains("&operationName="));
        assertFalse(resultUrl.contains("&variables="));

        // Test passing base without trailing slash gets normalized
        String slashlessBase = "https://ui.mesh-java.test.ecp/graphiql";
        String normalizedUrl = configswitcher.mesh.ui.MeshDetailPanel.buildMeshBrowserUrl(slashlessBase, entry);
        assertTrue(normalizedUrl.startsWith("https://ui.mesh-java.test.ecp/graphiql/?query="));

        // Test user pasting the full URL with existing query into the address box
        String fullExampleUrl = resultUrl;
        String recomputed = configswitcher.mesh.ui.MeshDetailPanel.buildMeshBrowserUrl(fullExampleUrl, entry);
        assertEquals(resultUrl, recomputed, "Should strip existing ?query= from base URL before appending new query");

        // Test entry == null with pre-existing query string retains original
        String preserved = configswitcher.mesh.ui.MeshDetailPanel.buildMeshBrowserUrl(fullExampleUrl, null);
        assertEquals(fullExampleUrl, preserved);
    }

    @Test
    public void testSequentialRestTemplateRequestsOnSameThreadAreCapturedSeparately() {
        String[] req1 = {
                "2026-10-14 19:15:20.000 DEBUG [app,trc1,spn1] 100 --- [http-nio-50906-exec-3] o.s.web.client.RestTemplate : HTTP POST http://mesh-java.uat.ecp/graphql",
                "2026-10-14 19:15:20.010 DEBUG [app,trc1,spn1] 100 --- [http-nio-50906-exec-3] o.s.web.client.RestTemplate : Writing [{variables={}, query=query GetUserInfo { user { id } }}] as \"application/json\"",
                "2026-10-14 19:15:20.080 DEBUG [app,trc1,spn1] 100 --- [http-nio-50906-exec-3] o.s.web.client.RestTemplate : Response 200 OK"
        };

        String[] req2 = {
                "2026-10-14 19:15:25.000 DEBUG [app,trc2,spn2] 100 --- [http-nio-50906-exec-3] o.s.web.client.RestTemplate : HTTP POST http://mesh-java.uat.ecp/graphql",
                "2026-10-14 19:15:25.010 DEBUG [app,trc2,spn2] 100 --- [http-nio-50906-exec-3] o.s.web.client.RestTemplate : Writing [{variables={}, query=query GetOrderInfo { order { id } }}] as \"application/json\"",
                "2026-10-14 19:15:25.090 DEBUG [app,trc2,spn2] 100 --- [http-nio-50906-exec-3] o.s.web.client.RestTemplate : Response 200 OK"
        };

        for (String line : req1) {
            analyzer.processLine(line);
        }
        for (String line : req2) {
            analyzer.processLine(line);
        }

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(2, entries.size(), "Both sequential requests on the same thread must be captured");
        assertEquals(2, tableModel.getRowCount(), "Table model must contain both requests");

        MeshRequestEntry entry1 = entries.get(0);
        assertEquals("GetUserInfo", entry1.getOperationName());
        assertEquals("trc1", entry1.getTraceId());
        assertEquals(MeshRequestStatus.SUCCESS, entry1.getStatus());

        MeshRequestEntry entry2 = entries.get(1);
        assertEquals("GetOrderInfo", entry2.getOperationName());
        assertEquals("trc2", entry2.getTraceId());
        assertEquals(MeshRequestStatus.SUCCESS, entry2.getStatus());
    }

    @Test
    public void testDifferentMeshCallsInSameTraceArePreserved() {
        MeshRequestEntry first = new MeshRequestEntry(1, "2026-09-27 20:42:37.100", 1000L);
        first.setTraceId("shared-trace");
        first.setOperationName("Query1");

        MeshRequestEntry second = new MeshRequestEntry(2, "2026-09-27 20:42:37.200", 1100L);
        second.setTraceId("shared-trace");
        second.setOperationName("Query2");

        captureService.onEntryCreated(first);
        captureService.onEntryCreated(second);

        assertEquals(2, captureService.getEntries().size(),
                "Different Mesh calls in the same trace must be shown as separate records");
        assertEquals(2, tableModel.getRowCount());
    }

    @Test
    public void testMaxHistoryDeletesOldestAndRecordsNewest() {
        java.util.List<MeshRequestEntry> removedList = new java.util.ArrayList<>();
        captureService.addListener(new MeshCaptureService.MeshCaptureListener() {
            @Override
            public void onEntryAdded(MeshRequestEntry entry) {}
            @Override
            public void onEntryUpdated(MeshRequestEntry entry) {}
            @Override
            public void onEntryRemoved(MeshRequestEntry entry) {
                removedList.add(entry);
                tableModel.removeEntry(entry);
            }
            @Override
            public void onCleared() {}
        });

        int limit = 500;
        int totalToInsert = 520;
        for (int i = 1; i <= totalToInsert; i++) {
            String ts = String.format("2026-10-14 10:00:%02d.%03d", i / 1000, i % 1000);
            MeshRequestEntry e = new MeshRequestEntry(i, ts, 10000L + i);
            e.setOperationName("Op_" + i);
            captureService.onEntryCreated(e);
        }

        assertEquals(limit, captureService.getEntries().size(), "CaptureService must cap at maxHistory");
        assertEquals(limit, tableModel.getRowCount(), "TableModel must cap at maxHistory");
        assertEquals(20, removedList.size(), "20 oldest records should have triggered onEntryRemoved");

        // Oldest (1..20) must be removed
        assertEquals("Op_21", captureService.getEntries().get(0).getOperationName());
        assertEquals("Op_520", captureService.getEntries().get(limit - 1).getOperationName());
        assertEquals("Op_21", tableModel.getEntryAt(0).getOperationName());
        assertEquals("Op_520", tableModel.getEntryAt(limit - 1).getOperationName());
    }

    @Test
    public void testUserJsonLogsCheckDoesNotHaltRecording() {
        String jsonLineTemplate = "{\"timestamp\":\"2026-10-14T19:15:21.%03d+03:00\",\"threadName\":\"http-nio-50906-exec-3\"," +
                "\"module\":\"org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver$DefaultResolver\"," +
                "\"service\":\"SERVICE_NAME_IS_UNDEFINED\",\"destination\":\"SERVICE_DESTINATION_IS_UNDEFINED\",\"level\":\"DEBUG\"," +
                "\"traceId\":\"edcdb56a48b88e26\",\"spanId\":\"edcdb56a48b88e26\"," +
                "\"message\":\"Found key 'n2o.config.field.forbidden_ids' in PropertySource 'n2oProperties' with value of type String\"," +
                "\"context\":{\"businessContext\":null,\"tracingContext\":{\"traceId\":\"edcdb56a48b88e26\",\"spanId\":\"edcdb56a48b88e26\",\"parentSpanId\":null}," +
                "\"authContext\":{\"userId\":\"eec9d22d-6124-4e97-9e88-17f2d2a956d3\",\"requestSource\":null,\"sourceProtocol\":null}," +
                "\"txContext\":null,\"uiTaskContext\":null,\"userTaskContext\":null,\"customContexts\":{}},\"index\":\"ecp-null-debug\"}";

        for (int i = 0; i < 20; i++) {
            analyzer.processLine(String.format(jsonLineTemplate, i));
        }

        // Now an actual GraphQL request arrives on the same thread
        analyzer.processLine("{\"timestamp\":\"2026-10-14T19:15:22.000+03:00\",\"threadName\":\"http-nio-50906-exec-3\",\"level\":\"DEBUG\"," +
                "\"traceId\":\"edcdb56a48b88e26\",\"spanId\":\"edcdb56a48b88e26\",\"message\":\"HTTP POST http://mesh-java.uat.ecp/graphql\"}");
        analyzer.processLine("{\"timestamp\":\"2026-10-14T19:15:22.010+03:00\",\"threadName\":\"http-nio-50906-exec-3\",\"level\":\"DEBUG\"," +
                "\"traceId\":\"edcdb56a48b88e26\",\"spanId\":\"edcdb56a48b88e26\",\"message\":\"Writing [{variables={}, query=query UserCheck { id }}] as \\\"application/json\\\"\"}");
        analyzer.processLine("{\"timestamp\":\"2026-10-14T19:15:22.080+03:00\",\"threadName\":\"http-nio-50906-exec-3\",\"level\":\"DEBUG\"," +
                "\"traceId\":\"edcdb56a48b88e26\",\"spanId\":\"edcdb56a48b88e26\",\"message\":\"Response 200 OK\"}");

        assertEquals(1, captureService.getEntries().size());
        MeshRequestEntry entry = captureService.getEntries().get(0);
        assertEquals("UserCheck", entry.getOperationName());
        assertEquals("http-nio-50906-exec-3", entry.getThreadName());
        assertEquals(MeshRequestStatus.SUCCESS, entry.getStatus());
    }
}
