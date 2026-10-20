package configswitcher.mesh;

import configswitcher.mesh.model.MeshErrorType;
import configswitcher.mesh.model.MeshOperationType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import configswitcher.mesh.service.MeshCaptureService;
import configswitcher.mesh.service.MeshOutputAnalyzer;
import configswitcher.mesh.ui.MeshDetailPanel;
import configswitcher.mesh.ui.MeshRequestTableModel;
import configswitcher.mesh.ui.SourceNavigator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MeshErrorClassificationTest {

    private MeshCaptureService captureService;
    private MeshOutputAnalyzer analyzer;

    @BeforeEach
    public void setUp() {
        captureService = new MeshCaptureService(null);
        analyzer = captureService.getAnalyzer();
    }

    @Test
    public void testGraphQLValidationClassification() {
        String queryLine = "2026-09-27 12:00:00.000  INFO [app,trc1,spn1] 100 --- [t-1] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query ValidateUser { user { id } }";
        String errorResultLine = "2026-09-27 12:00:00.040  INFO [app,trc1,spn1] 100 --- [t-1] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {errors=[{message=Validation error of type FieldUndefined: Field 'dummy' in type 'User' is undefined, path=[user, dummy], extensions={code=ValidationError}}]}";

        analyzer.processLine(queryLine);
        analyzer.processLine(errorResultLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshRequestStatus.ERROR, entry.getStatus());
        assertEquals(MeshErrorType.GRAPHQL_VALIDATION_ERROR, entry.getErrorType());
        assertNotNull(entry.getRootCauseMessage());
        assertTrue(entry.getRootCauseMessage().contains("Field 'dummy' in type 'User' is undefined"));
        assertEquals("ValidationError", entry.getErrorCode());
        assertEquals("user / dummy", entry.getErrorPath());
    }

    @Test
    public void testGraphQLServerErrorClassification() {
        String queryLine = "2026-09-27 12:01:00.000  INFO [app,trc2,spn2] 100 --- [t-2] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query FetchData { getData { id } }";
        String errorResultLine = "2026-09-27 12:01:00.080  INFO [app,trc2,spn2] 100 --- [t-2] n.n.f.b.g.GraphQlDataProviderEngine : Query result: {errors=[{message=Internal Server Exception: database unreachable, path=[getData], extensions={code=INTERNAL_SERVER_ERROR}}]}";

        analyzer.processLine(queryLine);
        analyzer.processLine(errorResultLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshErrorType.GRAPHQL_SERVER_ERROR, entry.getErrorType());
        assertEquals("INTERNAL_SERVER_ERROR", entry.getErrorCode());
        assertEquals("getData", entry.getErrorPath());
        assertTrue(entry.getRootCauseMessage().contains("database unreachable"));
    }

    @Test
    public void testHttp5xxClassification() {
        String queryLine = "2026-09-27 12:02:00.000  INFO [app,trc3,spn3] 100 --- [t-3] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query RemoteCall { remote { id } }";
        String httpLine = "2026-09-27 12:02:00.050 DEBUG [app,trc3,spn3] 100 --- [t-3] o.s.web.client.RestTemplate : Response 500 Internal Server Error";

        analyzer.processLine(queryLine);
        analyzer.processLine(httpLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshErrorType.HTTP_5XX, entry.getErrorType());
        assertEquals("500 Internal Server Error", entry.getHttpStatus());
        assertEquals("500", entry.getErrorCode());
    }

    @Test
    public void testHttp4xxClassification() {
        String queryLine = "2026-09-27 12:03:00.000  INFO [app,trc4,spn4] 100 --- [t-4] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query AuthCheck { auth { token } }";
        String httpLine = "2026-09-27 12:03:00.030 DEBUG [app,trc4,spn4] 100 --- [t-4] o.s.web.client.RestTemplate : Response 401 Unauthorized";

        analyzer.processLine(queryLine);
        analyzer.processLine(httpLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshErrorType.HTTP_4XX, entry.getErrorType());
        assertEquals("401 Unauthorized", entry.getHttpStatus());
        assertEquals("401", entry.getErrorCode());
    }

    @Test
    public void testTimeoutClassification() {
        String queryLine = "2026-09-27 12:04:00.000  INFO [app,trc5,spn5] 100 --- [t-5] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query SlowOp { slow { value } }";
        String timeoutLine = "2026-09-27 12:04:05.000 ERROR [app,trc5,spn5] 100 --- [t-5] o.s.web.client.RestTemplate : java.net.SocketTimeoutException: Read timed out";

        analyzer.processLine(queryLine);
        analyzer.processLine(timeoutLine);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshErrorType.NETWORK_TIMEOUT, entry.getErrorType());
        assertEquals("TIMEOUT", entry.getErrorCode());
        assertTrue(entry.getRootCauseMessage().contains("SocketTimeoutException: Read timed out"));
    }

    @Test
    public void testAppExceptionWithStackTrace() {
        String queryLine = "2026-09-27 12:05:00.000  INFO [app,trc6,spn6] 100 --- [t-6] n.n.f.b.g.GraphQlDataProviderEngine : Execute GraphQL query: query Broken { broken { id } }";
        String errLine = "2026-09-27 12:05:00.020 ERROR [app,trc6,spn6] 100 --- [t-6] n.n.f.b.g.GraphQlDataProviderEngine : java.lang.NullPointerException: Cannot invoke \"User.getId()\" because \"user\" is null";
        String stack1 = "\tat ru.gov.pfr.ecp.fo.ko.FoKoApplication.main(FoKoApplication.java:45)";
        String stack2 = "\tat org.springframework.boot.SpringApplication.run(SpringApplication.java:1300)";

        analyzer.processLine(queryLine);
        analyzer.processLine(errLine);
        analyzer.processLine(stack1);
        analyzer.processLine(stack2);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size());
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshErrorType.APP_EXCEPTION, entry.getErrorType());
        assertEquals("NullPointerException", entry.getErrorCode());
        assertTrue(entry.getRootCauseMessage().contains("Cannot invoke \"User.getId()\""));
        assertNotNull(entry.getRawStackTrace());
        assertTrue(entry.getRawStackTrace().contains("FoKoApplication.java:45"));
        assertTrue(entry.getRawStackTrace().contains("SpringApplication.java:1300"));
    }

    @Test
    public void testSourceNavigatorParsing() {
        String line = "    at ru.gov.pfr.ecp.fo.ko.service.UserService.findUser(UserService.java:82)";
        SourceNavigator.StackLocation loc = SourceNavigator.parseStackLine(line);
        assertNotNull(loc);
        assertEquals("ru.gov.pfr.ecp.fo.ko.service.UserService", loc.className());
        assertEquals("findUser", loc.methodName());
        assertEquals("UserService.java", loc.fileName());
        assertEquals(82, loc.lineNumber());
        assertTrue(loc.isUserCode());

        String springLine = "    at org.springframework.web.client.RestTemplate.execute(RestTemplate.java:710)";
        SourceNavigator.StackLocation springLoc = SourceNavigator.parseStackLine(springLine);
        assertNotNull(springLoc);
        assertEquals("org.springframework.web.client.RestTemplate", springLoc.className());
        assertFalse(springLoc.isUserCode());
    }

    @Test
    public void testTableModelErrorFiltersAndCounts() {
        MeshRequestTableModel model = new MeshRequestTableModel();

        MeshRequestEntry okEntry = new MeshRequestEntry(1, "12:00:00", 1000L);
        okEntry.setOperationType(MeshOperationType.QUERY);
        okEntry.setOperationName("opOK");
        okEntry.setRootField("OK");
        okEntry.setEndpoint("http://test");
        okEntry.setStatus(MeshRequestStatus.SUCCESS);
        okEntry.setHttpStatus("200 OK");
        okEntry.setDurationMs(50L);

        MeshRequestEntry gqlErrEntry = new MeshRequestEntry(2, "12:00:01", 1000L);
        gqlErrEntry.setOperationType(MeshOperationType.QUERY);
        gqlErrEntry.setOperationName("opGql");
        gqlErrEntry.setRootField("GqlErr");
        gqlErrEntry.setEndpoint("http://test");
        gqlErrEntry.setStatus(MeshRequestStatus.ERROR);
        gqlErrEntry.setErrorType(MeshErrorType.GRAPHQL_SERVER_ERROR);
        gqlErrEntry.setRootCauseMessage("Query failure");
        gqlErrEntry.setErrorCode("INTERNAL_SERVER_ERROR");
        gqlErrEntry.setDurationMs(120L);

        MeshRequestEntry httpErrEntry = new MeshRequestEntry(3, "12:00:02", 1000L);
        httpErrEntry.setOperationType(MeshOperationType.MUTATION);
        httpErrEntry.setOperationName("opHttp");
        httpErrEntry.setRootField("HttpErr");
        httpErrEntry.setEndpoint("http://test");
        httpErrEntry.setStatus(MeshRequestStatus.ERROR);
        httpErrEntry.setErrorType(MeshErrorType.HTTP_5XX);
        httpErrEntry.setHttpStatus("503 Service Unavailable");
        httpErrEntry.setDurationMs(200L);

        MeshRequestEntry timeoutEntry = new MeshRequestEntry(4, "12:00:03", 1000L);
        timeoutEntry.setOperationType(MeshOperationType.QUERY);
        timeoutEntry.setOperationName("opTimeout");
        timeoutEntry.setRootField("Timeout");
        timeoutEntry.setEndpoint("http://test");
        timeoutEntry.setStatus(MeshRequestStatus.ERROR);
        timeoutEntry.setErrorType(MeshErrorType.NETWORK_TIMEOUT);
        timeoutEntry.setRootCauseMessage("SocketTimeoutException: Read timed out");
        timeoutEntry.setDurationMs(5000L);

        model.addEntry(okEntry);
        model.addEntry(gqlErrEntry);
        model.addEntry(httpErrEntry);
        model.addEntry(timeoutEntry);

        assertEquals(4, model.getTotalCount());
        assertEquals(1, model.getSuccessCount());
        assertEquals(3, model.getErrorCount());
        assertEquals(1, model.getGraphQLErrorCount());
        assertEquals(1, model.getHttpErrorCount());
        assertEquals(1, model.getTimeoutCount());

        // Filter only errors
        model.setOnlyErrorsFilter(true);
        assertEquals(3, model.getRowCount());

        // Filter by specific error type
        model.setOnlyErrorsFilter(false);
        model.setErrorTypeFilter(MeshErrorType.NETWORK_TIMEOUT);
        assertEquals(1, model.getRowCount());
        assertEquals(4, model.getEntryAt(0).getId());

        // Reset filter
        model.setErrorTypeFilter(null);
        assertEquals(4, model.getRowCount());
    }

    @Test
    public void testTextualSearchAcrossErrorProperties() {
        MeshRequestEntry entry = new MeshRequestEntry(1, "12:00:00", 1000L);
        entry.setOperationType(MeshOperationType.QUERY);
        entry.setOperationName("getUser");
        entry.setRootField("user");
        entry.setEndpoint("http://test");
        entry.setStatus(MeshRequestStatus.ERROR);
        entry.setErrorType(MeshErrorType.GRAPHQL_SERVER_ERROR);
        entry.setRootCauseMessage("NullPointerException in UserService.calculateRating");
        entry.setErrorCode("ERR_CALC_FAILED");
        entry.setErrorPath("user / profile / rating");
        entry.setRawStackTrace("at ru.gov.pfr.ecp.fo.ko.service.UserService.calculateRating(UserService.java:100)");

        assertTrue(entry.matchesSearch("NullPointer"));
        assertTrue(entry.matchesSearch("calculateRating"));
        assertTrue(entry.matchesSearch("ERR_CALC_FAILED"));
        assertTrue(entry.matchesSearch("rating"));
        assertTrue(entry.matchesSearch("UserService.java"));
        assertFalse(entry.matchesSearch("nonExistentKeyword123"));
    }

    @Test
    public void testGenerateErrorReportMarkdown() {
        MeshRequestEntry entry = new MeshRequestEntry(42, "12:00:00", 1000L);
        entry.setOperationType(MeshOperationType.QUERY);
        entry.setOperationName("getUserDetails");
        entry.setRootField("userDetails");
        entry.setEndpoint("http://mesh/graphql");
        entry.setStatus(MeshRequestStatus.ERROR);
        entry.setErrorType(MeshErrorType.GRAPHQL_SERVER_ERROR);
        entry.setHttpStatus("500 Internal Server Error");
        entry.setErrorCode("INTERNAL_SERVER_ERROR");
        entry.setErrorPath("userDetails / address");
        entry.setRootCauseMessage("Connection refused to downstream address service");
        entry.setTraceId("trace-xyz-999");
        entry.setDurationMs(150L);
        entry.setFormattedQuery("query getUserDetails { userDetails { id address } }");
        entry.setFormattedVariables("{\"userId\": \"123\"}");
        entry.setRawStackTrace("at com.example.AddressClient.connect(AddressClient.java:55)");

        String report = MeshDetailPanel.generateErrorReport(entry);
        assertNotNull(report);
        assertTrue(report.contains("### GraphQL / Service Error Report"));
        assertTrue(report.contains("| **Error Type** | `GraphQL Server Error` |"));
        assertTrue(report.contains("| **Error Code** | `INTERNAL_SERVER_ERROR` |"));
        assertTrue(report.contains("| **Error Path** | `userDetails / address` |"));
        assertTrue(report.contains("| **Trace ID** | `trace-xyz-999` |"));
        assertTrue(report.contains("Connection refused to downstream address service"));
        assertTrue(report.contains("query getUserDetails"));
        assertTrue(report.contains("AddressClient.java:55"));
    }

    @Test
    public void testStandaloneJsonErrorWithStackTrace() {
        String jsonError = "{\"timestamp\":\"2026-09-27T20:08:04.133+03:00\",\"threadName\":\"http-nio-50906-exec-9\"," +
                "\"module\":\"org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[appConfigServlet]\"," +
                "\"level\":\"ERROR\",\"traceId\":\"ab2ac3b3c2169879\"," +
                "\"message\":\"Servlet.service() for servlet [appConfigServlet] in context with path [] threw exception\"," +
                "\"Exception\":\"ru.listen2u.ps.authz.exception.AuthzServerUnavailableException: Сервис authz недоступен\\r\\n\\tat ru.listen2u.ps.authz.service.AuthzClient.check(AuthzClient.java:50)\\r\\nCaused by: io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\\r\\nCaused by: java.net.ConnectException: Connection refused: getsockopt\\r\\n\\tat java.base/sun.nio.ch.Net.pollConnect(Native Method)\"}";

        analyzer.processLine(jsonError);

        List<MeshRequestEntry> entries = captureService.getEntries();
        assertEquals(1, entries.size(), "Standalone JSON error must be captured into entries");
        MeshRequestEntry entry = entries.get(0);

        assertTrue(entry.hasError());
        assertEquals(MeshRequestStatus.ERROR, entry.getStatus());
        assertEquals("ERROR", entry.getLogLevel());
        assertEquals("appConfigServlet", entry.getOperationName());
        assertEquals("ab2ac3b3c2169879", entry.getTraceId());
        assertEquals(MeshErrorType.NETWORK_TIMEOUT, entry.getErrorType());
        assertNotNull(entry.getRawStackTrace());
        assertTrue(entry.getRawStackTrace().contains("AuthzServerUnavailableException"));
        assertTrue(entry.getRawStackTrace().contains("Connection refused: getsockopt"));
        assertNotNull(entry.getRootCauseMessage());
        assertTrue(entry.getRootCauseMessage().contains("Connection refused: getsockopt"));
    }

    @Test
    public void testTableModelLevelFilter() {
        MeshRequestTableModel model = new MeshRequestTableModel();

        MeshRequestEntry infoEntry = new MeshRequestEntry(1, "12:00:00", 1000L);
        infoEntry.setLogLevel("INFO");
        infoEntry.setStatus(MeshRequestStatus.SUCCESS);

        MeshRequestEntry warnEntry = new MeshRequestEntry(2, "12:00:01", 1000L);
        warnEntry.setLogLevel("WARN");
        warnEntry.setStatus(MeshRequestStatus.WARN);

        MeshRequestEntry errorEntry = new MeshRequestEntry(3, "12:00:02", 1000L);
        errorEntry.setLogLevel("ERROR");
        errorEntry.setStatus(MeshRequestStatus.ERROR);

        MeshRequestEntry debugEntry = new MeshRequestEntry(4, "12:00:03", 1000L);
        debugEntry.setLogLevel("DEBUG");
        debugEntry.setStatus(MeshRequestStatus.SUCCESS);

        model.addEntry(infoEntry);
        model.addEntry(warnEntry);
        model.addEntry(errorEntry);
        model.addEntry(debugEntry);

        assertEquals(4, model.getRowCount());

        model.setLevelFilter("ERROR");
        assertEquals(1, model.getRowCount());
        assertEquals(3, model.getEntryAt(0).getId());

        model.setLevelFilter("WARN");
        assertEquals(1, model.getRowCount());
        assertEquals(2, model.getEntryAt(0).getId());

        model.setLevelFilter("INFO");
        assertEquals(1, model.getRowCount());
        assertEquals(1, model.getEntryAt(0).getId());

        model.setLevelFilter("DEBUG");
        assertEquals(1, model.getRowCount());
        assertEquals(4, model.getEntryAt(0).getId());

        model.setLevelFilter("All Levels");
        assertEquals(4, model.getRowCount());
    }

    @Test
    public void testDetectLogLevelForJsonLines() {
        String jsonDebug = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"level\":\"DEBUG\",\"message\":\"Found key\"}";
        String jsonInfo = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"level\":\"INFO\",\"message\":\"Started\"}";
        String jsonWarn = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"level\":\"WARN\",\"message\":\"Deprecated property\"}";
        String jsonError = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"threadName\":\"http-nio-50906-exec-8\",\"level\":\"ERROR\",\"message\":\"Crash\"}";

        assertEquals(configswitcher.util.TerminalOutputFilter.LogLevel.DEBUG, configswitcher.util.TerminalOutputFilter.detectLogLevel(jsonDebug));
        assertEquals(configswitcher.util.TerminalOutputFilter.LogLevel.INFO, configswitcher.util.TerminalOutputFilter.detectLogLevel(jsonInfo));
        assertEquals(configswitcher.util.TerminalOutputFilter.LogLevel.WARN, configswitcher.util.TerminalOutputFilter.detectLogLevel(jsonWarn));
        assertEquals(configswitcher.util.TerminalOutputFilter.LogLevel.ERROR, configswitcher.util.TerminalOutputFilter.detectLogLevel(jsonError));
    }

    @Test
    public void testIsMeshRecord() {
        MeshRequestEntry emptyEntry = new MeshRequestEntry(1, "12:00:00", 1000L);
        assertFalse(emptyEntry.isMeshRecord());

        emptyEntry.setRawQuery("query { user { id } }");
        assertTrue(emptyEntry.isMeshRecord());
        assertEquals(MeshOperationType.QUERY, emptyEntry.getOperationType());

        MeshRequestEntry appError = new MeshRequestEntry(2, "12:00:00", 1000L);
        appError.setErrorMessage("NullPointerException at com.example.Service");
        assertFalse(appError.isMeshRecord());
        assertEquals(MeshOperationType.UNKNOWN, appError.getOperationType());

        MeshRequestEntry mutationEntry = new MeshRequestEntry(3, "12:00:00", 1000L);
        mutationEntry.setOperationType(MeshOperationType.MUTATION);
        assertTrue(mutationEntry.isMeshRecord());
        assertEquals(MeshOperationType.MUTATION, mutationEntry.getOperationType());

        MeshRequestEntry endpointEntry = new MeshRequestEntry(4, "12:00:00", 1000L);
        endpointEntry.setEndpoint("http://localhost:8080/graphql");
        assertFalse(endpointEntry.isMeshRecord()); // UNKNOWN type should not be mesh record

        endpointEntry.setOperationType(MeshOperationType.QUERY);
        assertTrue(endpointEntry.isMeshRecord());

        MeshRequestEntry subEntry = new MeshRequestEntry(5, "12:00:00", 1000L);
        subEntry.setOperationType(MeshOperationType.SUBSCRIPTION);
        assertFalse(subEntry.isMeshRecord()); // SUBSCRIPTION should not be mesh record

        MeshRequestEntry backendErr = new MeshRequestEntry(6, "12:00:00", 1000L);
        backendErr.setOperationName("Backend Error");
        backendErr.setRootField("NullPointerException: failed");
        assertFalse(backendErr.isMeshRecord()); // Backend logs with simpleLogger should not be mesh record
    }

    @Test
    public void testTableModelOnlyMeshFilter() {
        MeshRequestTableModel model = new MeshRequestTableModel();

        MeshRequestEntry meshQueryEntry = new MeshRequestEntry(1, "12:00:00", 1000L);
        meshQueryEntry.setRawQuery("query { user { id } }");
        meshQueryEntry.setStatus(MeshRequestStatus.SUCCESS);

        MeshRequestEntry appErrorEntry = new MeshRequestEntry(2, "12:00:01", 1000L);
        appErrorEntry.setErrorMessage("NullPointerException");
        appErrorEntry.setStatus(MeshRequestStatus.ERROR);

        MeshRequestEntry meshErrorEntry = new MeshRequestEntry(3, "12:00:02", 1000L);
        meshErrorEntry.setRawQuery("query { failed { id } }");
        meshErrorEntry.setStatus(MeshRequestStatus.ERROR);

        MeshRequestEntry meshMutationEntry = new MeshRequestEntry(4, "12:00:03", 1000L);
        meshMutationEntry.setRawQuery("mutation CreateUser { createUser { id } }");
        meshMutationEntry.setStatus(MeshRequestStatus.SUCCESS);

        MeshRequestEntry subscriptionEntry = new MeshRequestEntry(5, "12:00:04", 1000L);
        subscriptionEntry.setOperationType(MeshOperationType.SUBSCRIPTION);
        subscriptionEntry.setStatus(MeshRequestStatus.SUCCESS);

        model.addEntry(meshQueryEntry);
        model.addEntry(appErrorEntry);
        model.addEntry(meshErrorEntry);
        model.addEntry(meshMutationEntry);
        model.addEntry(subscriptionEntry);

        assertEquals(5, model.getTotalCount());
        assertEquals(3, model.getMeshCount()); // query 1, query 3, mutation 4
        assertEquals(2, model.getErrorCount());
        assertEquals(5, model.getRowCount());

        // Enable onlyMeshFilter: only QUERY (1, 3) and MUTATION (4) should be shown
        model.setOnlyMeshFilter(true);
        assertTrue(model.isOnlyMeshFilter());
        assertEquals(3, model.getRowCount());
        assertEquals(1, model.getEntryAt(0).getId());
        assertEquals(3, model.getEntryAt(1).getId());
        assertEquals(4, model.getEntryAt(2).getId());

        // Also enable onlyErrorsFilter (mesh + errors): only query 3 has error
        model.setOnlyErrorsFilter(true);
        assertEquals(1, model.getRowCount());
        assertEquals(3, model.getEntryAt(0).getId());

        // Disable onlyErrorsFilter: back to 3 mesh entries (query & mutation only)
        model.setOnlyErrorsFilter(false);
        assertEquals(3, model.getRowCount());

        // Disable onlyMeshFilter
        model.setOnlyMeshFilter(false);
        assertFalse(model.isOnlyMeshFilter());
        assertEquals(5, model.getRowCount());
    }

    @Test
    public void testTableColumnsDoNotIncludeLatency() {
        MeshRequestTableModel model = new MeshRequestTableModel();
        assertEquals(7, model.getColumnCount(), "Table model must have exactly 7 columns");

        List<String> colNames = new ArrayList<>();
        for (int i = 0; i < model.getColumnCount(); i++) {
            colNames.add(model.getColumnName(i));
        }

        assertEquals(List.of("#", "Time", "Level", "Status", "HTTP", "Type", "Operation"), colNames);
        assertFalse(colNames.contains("Latency"), "Table columns must not contain Latency");
    }

    @Test
    public void testStatusOkFilterShowsOkRecordsOnly() {
        MeshRequestTableModel model = new MeshRequestTableModel();

        MeshRequestEntry okEntry1 = new MeshRequestEntry(1, "12:00:00", 1000L);
        okEntry1.setStatus(MeshRequestStatus.SUCCESS);

        MeshRequestEntry appExcWith200 = new MeshRequestEntry(2, "12:00:01", 1000L);
        appExcWith200.setStatus(MeshRequestStatus.SUCCESS);
        appExcWith200.setErrorType(MeshErrorType.APP_EXCEPTION);
        appExcWith200.setErrorMessage("Crash");

        MeshRequestEntry pendingEntry = new MeshRequestEntry(3, "12:00:02", 1000L);
        pendingEntry.setStatus(MeshRequestStatus.PENDING);

        MeshRequestEntry errEntry = new MeshRequestEntry(4, "12:00:03", 1000L);
        errEntry.setStatus(MeshRequestStatus.ERROR);

        MeshRequestEntry okEntry2 = new MeshRequestEntry(5, "12:00:04", 1000L);
        okEntry2.setStatus(MeshRequestStatus.SUCCESS);

        model.addEntry(okEntry1);
        model.addEntry(appExcWith200);
        model.addEntry(pendingEntry);
        model.addEntry(errEntry);
        model.addEntry(okEntry2);

        assertEquals(5, model.getTotalCount());
        assertEquals(2, model.getSuccessCount()); // Only entries 1 and 5 (genuine OK)
        assertEquals(2, model.getErrorCount());   // Entries 2 and 4

        // Apply Status = OK filter (MeshRequestStatus.SUCCESS)
        model.setStatusFilter(MeshRequestStatus.SUCCESS);
        assertEquals(2, model.getRowCount(), "Status OK filter must show OK records only");
        assertEquals(1, model.getEntryAt(0).getId());
        assertEquals(5, model.getEntryAt(1).getId());
    }
}
