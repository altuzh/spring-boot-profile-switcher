package configswitcher.mesh.ui;

import configswitcher.mesh.service.MeshCacheProxy.RequestView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshCacheProxyTableTest {
    @Test
    void separatesCacheResultsAndRefreshActionsIntoFiveColumns() {
        var model = new MeshCacheProxyPanel.RequestTableModel(null);
        model.setRows(List.of(request("HIT", 200), request("REFRESH", 503)));

        assertEquals(List.of("Operation", "Root Field", "Cache", "Action", "HTTP"),
                IntStream.range(0, model.getColumnCount()).mapToObj(model::getColumnName).toList());
        assertEquals(List.of("Read", "customer", "HIT", "", 200),
                IntStream.range(0, model.getColumnCount()).mapToObj(column -> model.getValueAt(0, column)).toList());
        assertEquals(List.of("Read", "customer", "", "REFRESH", 503),
                IntStream.range(0, model.getColumnCount()).mapToObj(column -> model.getValueAt(1, column)).toList());
    }

    private static RequestView request(String cacheStatus, int httpStatus) {
        return new RequestView(1, Instant.EPOCH, "Read", "customer", httpStatus, cacheStatus,
                "", "", true, "", "", "", 0, "", "");
    }
}
