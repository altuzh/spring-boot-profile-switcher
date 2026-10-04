package configswitcher.mesh.ui;

import configswitcher.mesh.model.MeshOperationType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class MeshRequestTableModel extends AbstractTableModel {

    private static final String[] COLUMN_NAMES = {
            "#",
            "Time",
            "Status",
            "HTTP",
            "Type",
            "Operation"
    };

    private static final Class<?>[] COLUMN_CLASSES = {
            Integer.class,
            String.class,
            Object.class,
            String.class,
            String.class,
            String.class
    };

    private static final String[] COLUMN_I18N_KEYS = {
            "mesh.table.col.num",
            "mesh.table.col.time",
            "mesh.table.col.status",
            "mesh.table.col.http",
            "mesh.table.col.type",
            "mesh.table.col.operation"
    };

    private final com.intellij.openapi.project.Project project;
    private final List<MeshRequestEntry> allEntries = new ArrayList<>();
    private final List<MeshRequestEntry> filteredEntries = new ArrayList<>();

    public MeshRequestTableModel() {
        this(null);
    }

    public MeshRequestTableModel(@Nullable com.intellij.openapi.project.Project project) {
        this.project = project;
    }

    private String searchText = "";
    private String levelFilter = null;
    private MeshOperationType typeFilter = null;
    private MeshRequestStatus statusFilter = null;
    private configswitcher.mesh.model.MeshErrorType errorTypeFilter = null;
    private boolean onlyErrorsFilter = false;
    private boolean onlyMeshFilter = false;

    @Override
    public int getRowCount() {
        return filteredEntries.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        if (column >= 0 && column < COLUMN_I18N_KEYS.length) {
            return configswitcher.i18n.I18n.get(project, COLUMN_I18N_KEYS[column]);
        }
        return COLUMN_NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return COLUMN_CLASSES[columnIndex];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= filteredEntries.size()) {
            return null;
        }
        MeshRequestEntry entry = filteredEntries.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> entry.getId();
            case 1 -> entry.getFormattedTime();
            case 2 -> entry; // Pass whole entry for status & error badge renderer
            case 3 -> entry.getHttpStatus() != null ? entry.getHttpStatus() : "-";
            case 4 -> entry.getOperationType().getDisplayName();
            case 5 -> entry.getDisplayOperation();
            default -> null;
        };
    }

    public synchronized @Nullable MeshRequestEntry getEntryAt(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < filteredEntries.size()) {
            return filteredEntries.get(rowIndex);
        }
        return null;
    }

    public synchronized void setEntries(@NotNull List<MeshRequestEntry> newEntries) {
        allEntries.clear();
        java.util.Set<String> seenTimestamps = new java.util.HashSet<>();
        java.util.Set<Integer> seenIds = new java.util.HashSet<>();
        for (MeshRequestEntry entry : newEntries) {
            String ts = entry.getTimestamp();
            if (ts != null && !seenTimestamps.add(ts)) {
                continue;
            }
            if (!seenIds.add(entry.getId())) {
                continue;
            }
            allEntries.add(entry);
        }
        applyFilter();
    }

    public synchronized void addEntry(@NotNull MeshRequestEntry entry) {
        // Enforce uniqueness by timestamp and id
        for (int i = 0; i < allEntries.size(); i++) {
            MeshRequestEntry existing = allEntries.get(i);
            if (existing.getId() == entry.getId() ||
                    (existing.getTimestamp() != null && existing.getTimestamp().equals(entry.getTimestamp()))) {
                allEntries.set(i, entry);
                updateFilteredEntry(entry);
                return;
            }
        }

        allEntries.add(entry);
        if (matches(entry)) {
            filteredEntries.add(entry);
            int row = filteredEntries.size() - 1;
            fireTableRowsInserted(row, row);
        }
    }

    private void updateFilteredEntry(@NotNull MeshRequestEntry entry) {
        for (int i = 0; i < filteredEntries.size(); i++) {
            MeshRequestEntry existing = filteredEntries.get(i);
            if (existing.getId() == entry.getId() ||
                    (existing.getTimestamp() != null && existing.getTimestamp().equals(entry.getTimestamp()))) {
                if (matches(entry)) {
                    filteredEntries.set(i, entry);
                    fireTableRowsUpdated(i, i);
                } else {
                    filteredEntries.remove(i);
                    fireTableRowsDeleted(i, i);
                }
                return;
            }
        }

        // If not present in filtered, but matches now
        if (matches(entry)) {
            filteredEntries.add(entry);
            int row = filteredEntries.size() - 1;
            fireTableRowsInserted(row, row);
        }
    }

    public synchronized void updateEntry(@NotNull MeshRequestEntry entry) {
        // Find in allEntries
        for (int i = 0; i < allEntries.size(); i++) {
            MeshRequestEntry existing = allEntries.get(i);
            if (existing.getId() == entry.getId() ||
                    (existing.getTimestamp() != null && existing.getTimestamp().equals(entry.getTimestamp()))) {
                allEntries.set(i, entry);
                break;
            }
        }

        updateFilteredEntry(entry);
    }

    public synchronized void clear() {
        allEntries.clear();
        filteredEntries.clear();
        fireTableDataChanged();
    }

    public synchronized void setSearchText(@Nullable String text) {
        this.searchText = text != null ? text.trim().toLowerCase() : "";
        applyFilter();
    }

    public synchronized void setLevelFilter(@Nullable String levelFilter) {
        if (levelFilter == null || "All Levels".equalsIgnoreCase(levelFilter) || "ALL".equalsIgnoreCase(levelFilter)) {
            this.levelFilter = null;
        } else {
            this.levelFilter = levelFilter.toUpperCase().trim();
        }
        applyFilter();
    }

    public synchronized void setTypeFilter(@Nullable MeshOperationType typeFilter) {
        this.typeFilter = typeFilter;
        applyFilter();
    }

    public synchronized void setStatusFilter(@Nullable MeshRequestStatus statusFilter) {
        this.statusFilter = statusFilter;
        applyFilter();
    }

    public synchronized void setErrorTypeFilter(@Nullable configswitcher.mesh.model.MeshErrorType errorTypeFilter) {
        this.errorTypeFilter = errorTypeFilter;
        applyFilter();
    }

    public synchronized void setOnlyErrorsFilter(boolean onlyErrorsFilter) {
        this.onlyErrorsFilter = onlyErrorsFilter;
        applyFilter();
    }

    public synchronized void setOnlyMeshFilter(boolean onlyMeshFilter) {
        this.onlyMeshFilter = onlyMeshFilter;
        applyFilter();
    }

    public synchronized boolean isOnlyMeshFilter() {
        return onlyMeshFilter;
    }

    public synchronized void applyFilter() {
        filteredEntries.clear();
        for (MeshRequestEntry entry : allEntries) {
            if (matches(entry)) {
                filteredEntries.add(entry);
            }
        }
        fireTableDataChanged();
    }

    private boolean matches(MeshRequestEntry entry) {
        if (levelFilter != null && !levelFilter.equalsIgnoreCase(entry.getLogLevel())) {
            return false;
        }
        if (typeFilter != null && entry.getOperationType() != typeFilter) {
            return false;
        }
        if (statusFilter != null) {
            if (statusFilter == MeshRequestStatus.SUCCESS) {
                if (entry.getStatus() != MeshRequestStatus.SUCCESS || entry.hasError()) {
                    return false;
                }
            } else if (entry.getStatus() != statusFilter) {
                return false;
            }
        }
        if (onlyErrorsFilter && !entry.hasError()) {
            return false;
        }
        if (onlyMeshFilter && !entry.isMeshRecord()) {
            return false;
        }
        if (errorTypeFilter != null && entry.getErrorType() != errorTypeFilter) {
            return false;
        }
        if (!searchText.isEmpty() && !entry.matchesSearch(searchText)) {
            return false;
        }
        return true;
    }

    public synchronized int getTotalCount() {
        return allEntries.size();
    }

    public synchronized int getSuccessCount() {
        return (int) allEntries.stream().filter(e -> e.getStatus() == MeshRequestStatus.SUCCESS && !e.hasError()).count();
    }

    public synchronized int getErrorCount() {
        return (int) allEntries.stream().filter(MeshRequestEntry::hasError).count();
    }

    public synchronized int getMeshCount() {
        return (int) allEntries.stream().filter(MeshRequestEntry::isMeshRecord).count();
    }

    public synchronized int getGraphQLErrorCount() {
        return (int) allEntries.stream().filter(e ->
                e.getErrorType() == configswitcher.mesh.model.MeshErrorType.GRAPHQL_SERVER_ERROR ||
                e.getErrorType() == configswitcher.mesh.model.MeshErrorType.GRAPHQL_VALIDATION_ERROR).count();
    }

    public synchronized int getHttpErrorCount() {
        return (int) allEntries.stream().filter(e ->
                e.getErrorType() == configswitcher.mesh.model.MeshErrorType.HTTP_5XX ||
                e.getErrorType() == configswitcher.mesh.model.MeshErrorType.HTTP_4XX).count();
    }

    public synchronized int getTimeoutCount() {
        return (int) allEntries.stream().filter(e ->
                e.getErrorType() == configswitcher.mesh.model.MeshErrorType.NETWORK_TIMEOUT).count();
    }

    public synchronized double getAverageLatencyMs() {
        return allEntries.stream()
                .filter(e -> e.getDurationMs() != null)
                .mapToLong(MeshRequestEntry::getDurationMs)
                .average()
                .orElse(0.0);
    }
}
