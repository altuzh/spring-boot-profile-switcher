package configswitcher.mesh.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.mesh.model.MeshErrorType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.service.MeshCaptureService;
import configswitcher.service.SessionLogManager;
import configswitcher.ui.HelpLabel;
import configswitcher.util.ConfigNotifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated Error Analysis and Diagnostic Studio.
 * Focuses exclusively on identifying, grouping, searching, and diagnosing errors
 * across GraphQL Mesh operations and backend Spring Boot logs.
 */
public class MeshErrorAnalysisPanel extends JPanel implements MeshCaptureService.MeshCaptureListener, Disposable {

    private final Project project;
    private final MeshCaptureService captureService;
    private final MeshRequestTableModel errorTableModel;
    private final JBTable errorTable;
    private final MeshDetailPanel detailPanel;

    private final CardLayout centerCardLayout = new CardLayout();
    private final JPanel centerPanel = new JPanel(centerCardLayout);
    private final JPanel emptyStatePanel = new JPanel(new BorderLayout());
    private final JPanel contentSplitterPanel = new JPanel(new BorderLayout());

    // Toolbar controls
    private final SearchTextField searchField = new SearchTextField();
    private final ComboBox<String> errorTypeCombo = new ComboBox<>(new String[]{
            "All Errors",
            "GraphQL Server Error",
            "GraphQL Validation Error",
            "HTTP 5xx (Server)",
            "HTTP 4xx (Client)",
            "Network Timeout",
            "App Exception"
    });

    // Metric Chips (Buttons)
    private final JButton chipAll = createMetricChip("All Errors (0)", JBColor.RED);
    private final JButton chipGql = createMetricChip("GraphQL (0)", JBColor.RED);
    private final JButton chipValidation = createMetricChip("Validation (0)", new JBColor(new java.awt.Color(230, 81, 0), new java.awt.Color(255, 183, 77)));
    private final JButton chipHttp5xx = createMetricChip("HTTP 5xx (0)", JBColor.RED);
    private final JButton chipHttp4xx = createMetricChip("HTTP 4xx (0)", new JBColor(new java.awt.Color(230, 81, 0), new java.awt.Color(255, 183, 77)));
    private final JButton chipTimeout = createMetricChip("Timeout (0)", JBColor.RED);
    private final JButton chipApp = createMetricChip("App Exception (0)", JBColor.RED);

    public MeshErrorAnalysisPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.captureService = MeshCaptureService.getInstance(project);

        this.errorTableModel = new MeshRequestTableModel(project);
        this.errorTableModel.setOnlyErrorsFilter(true); // Exclusively errors
        this.errorTable = new JBTable(errorTableModel);
        this.detailPanel = new MeshDetailPanel(project);

        initUI();
        initListeners();
        reloadEntries();
    }

    private void initUI() {
        // --- Top Bar: Metrics & Quick Filter Chips ---
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));

        JPanel chipsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        chipsBar.setBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0));
        JBLabel filterByLabel = new JBLabel(I18n.get(project, "mesh.label.error_types"));
        filterByLabel.setFont(filterByLabel.getFont().deriveFont(Font.BOLD));
        chipsBar.add(filterByLabel);
        chipsBar.add(chipAll);
        chipsBar.add(chipGql);
        chipsBar.add(chipValidation);
        chipsBar.add(chipHttp5xx);
        chipsBar.add(chipHttp4xx);
        chipsBar.add(chipTimeout);
        chipsBar.add(chipApp);
        chipsBar.add(new HelpLabel(I18n.get(project, "help.mesh.error_categories")));
        topContainer.add(chipsBar);

        // --- Toolbar ---
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchField.getTextEditor().getEmptyText().setText(I18n.get(project, "mesh.error.search_hint"));
        searchField.getTextEditor().setColumns(28);
        toolbar.add(searchField);

        toolbar.add(new JBLabel(I18n.get(project, "mesh.label.category")));
        errorTypeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String s) {
                    String localized = switch (s) {
                        case "All Errors" -> I18n.get(project, "mesh.filter.all_errors");
                        case "GraphQL Server Error" -> I18n.get(project, "mesh.filter.gql_server");
                        case "GraphQL Validation Error" -> I18n.get(project, "mesh.filter.gql_validation");
                        case "HTTP 5xx (Server)" -> I18n.get(project, "mesh.filter.http5xx");
                        case "HTTP 4xx (Client)" -> I18n.get(project, "mesh.filter.http4xx");
                        case "Network Timeout" -> I18n.get(project, "mesh.filter.timeout");
                        case "App Exception" -> I18n.get(project, "mesh.filter.app_exception");
                        default -> s;
                    };
                    setText(localized);
                }
                return c;
            }
        });
        toolbar.add(errorTypeCombo);

        JButton scanLogBtn = new JButton(I18n.get(project, "mesh.btn.scan_log"), AllIcons.Actions.Find);
        scanLogBtn.setToolTipText(I18n.get(project, "mesh.btn.scan_log.tooltip"));
        scanLogBtn.addActionListener(e -> {
            int lines = SessionLogManager.getInstance(project).scanSessionLogInAnalyzer();
            if (lines > 0) {
                ConfigNotifier.notifyInfo(project, "Scanned " + lines + " lines from session.log.");
            } else {
                ConfigNotifier.notifyInfo(project, "session.log is currently empty. Run your application to generate logs.");
            }
        });
        toolbar.add(scanLogBtn);

        JButton pasteLogBtn = new JButton(I18n.get(project, "mesh.btn.paste_log"), AllIcons.Actions.MenuPaste);
        pasteLogBtn.setToolTipText(I18n.get(project, "mesh.btn.paste_log.tooltip"));
        pasteLogBtn.addActionListener(e -> new PasteLogDialog(project).show());
        toolbar.add(pasteLogBtn);

        JButton exportAllBtn = new JButton(I18n.get(project, "mesh.btn.export_report"), AllIcons.ToolbarDecorator.Export);
        exportAllBtn.setToolTipText(I18n.get(project, "mesh.btn.export_report.tooltip"));
        exportAllBtn.addActionListener(e -> exportFullErrorReport());
        toolbar.add(exportAllBtn);

        JButton clearBtn = new JButton(I18n.get(project, "mesh.btn.clear"), AllIcons.Actions.GC);
        clearBtn.setToolTipText(I18n.get(project, "mesh.btn.clear.tooltip"));
        clearBtn.addActionListener(e -> captureService.clear());
        toolbar.add(clearBtn);

        toolbar.add(new HelpLabel(I18n.get(project, "help.mesh.error_categories")));

        topContainer.add(toolbar);
        add(topContainer, BorderLayout.NORTH);

        // --- Empty State Panel ---
        setupEmptyStatePanel();

        // --- Splitter & Table ---
        errorTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        errorTable.setAutoResizeMode(JBTable.AUTO_RESIZE_LAST_COLUMN);
        errorTable.setStriped(true);

        if (errorTable.getColumnModel().getColumnCount() >= 6) {
            errorTable.getColumnModel().getColumn(0).setPreferredWidth(45);
            errorTable.getColumnModel().getColumn(0).setMaxWidth(60);
            errorTable.getColumnModel().getColumn(1).setPreferredWidth(75);
            errorTable.getColumnModel().getColumn(1).setMaxWidth(90);
            errorTable.getColumnModel().getColumn(2).setPreferredWidth(95); // Status / Error badge
            errorTable.getColumnModel().getColumn(2).setMaxWidth(115);
            errorTable.getColumnModel().getColumn(3).setPreferredWidth(80);
            errorTable.getColumnModel().getColumn(3).setMaxWidth(100);
            errorTable.getColumnModel().getColumn(4).setPreferredWidth(75);
            errorTable.getColumnModel().getColumn(4).setMaxWidth(90);
            errorTable.getColumnModel().getColumn(5).setPreferredWidth(220); // Operation
        }

        // Custom Cell Renderer for error badge
        errorTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable t, Object val, boolean isSelected, boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, isSelected, hasFocus, row, col);
                if (val instanceof MeshRequestEntry entry) {
                    MeshErrorType errType = entry.getErrorType();
                    if (errType != null) {
                        setText(errType.getBadgeText());
                        setIcon(errType.getIcon());
                        if (!isSelected) {
                            setForeground(errType.getColor());
                        }
                    } else {
                        setText("ERR");
                        setIcon(AllIcons.General.Error);
                        if (!isSelected) {
                            setForeground(JBColor.RED);
                        }
                    }
                    String tooltip = entry.getRootCauseMessage() != null ? entry.getRootCauseMessage() : entry.getErrorMessage();
                    setToolTipText(tooltip != null ? tooltip : "Error occurred");
                }
                return c;
            }
        });

        OnePixelSplitter splitter = new OnePixelSplitter(false, 0.45f);
        splitter.setHonorComponentsMinimumSize(false);
        splitter.setAndLoadSplitterProportionKey("MeshErrorAnalysis.splitterProportion");
        JBScrollPane errorTableScrollPane = new JBScrollPane(errorTable);
        errorTableScrollPane.setMinimumSize(new Dimension(100, 100));
        splitter.setFirstComponent(errorTableScrollPane);
        splitter.setSecondComponent(detailPanel);
        contentSplitterPanel.add(splitter, BorderLayout.CENTER);

        centerPanel.add(emptyStatePanel, "EMPTY");
        centerPanel.add(contentSplitterPanel, "CONTENT");
        add(centerPanel, BorderLayout.CENTER);

        centerCardLayout.show(centerPanel, "EMPTY");
    }

    private void setupEmptyStatePanel() {
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(JBUI.Borders.empty(40));

        JBLabel iconLabel = new JBLabel(AllIcons.General.InspectionsOK);
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel titleLabel = new JBLabel(I18n.get(project, "mesh.error.empty_title"), JBLabel.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel subLabel = new JBLabel(I18n.get(project, "mesh.error.empty_sub"), JBLabel.CENTER);
        subLabel.setForeground(JBColor.GRAY);
        subLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        JButton scanBtn = new JButton(I18n.get(project, "mesh.error.empty_scan_btn"), AllIcons.Actions.Find);
        scanBtn.setToolTipText(I18n.get(project, "mesh.btn.scan_log.tooltip"));
        scanBtn.addActionListener(e -> {
            int lines = SessionLogManager.getInstance(project).scanSessionLogInAnalyzer();
            if (lines > 0) {
                ConfigNotifier.notifyInfo(project, "Scanned " + lines + " lines from session.log.");
            } else {
                ConfigNotifier.notifyInfo(project, "session.log is empty. Run your application to generate logs.");
            }
        });

        JButton pasteBtn = new JButton(I18n.get(project, "mesh.error.empty_paste_btn"), AllIcons.Actions.MenuPaste);
        pasteBtn.setToolTipText(I18n.get(project, "mesh.btn.paste_log.tooltip"));
        pasteBtn.addActionListener(e -> new PasteLogDialog(project).show());

        JButton logFolderBtn = new JButton(I18n.get(project, "mesh.error.empty_folder_btn"), AllIcons.Nodes.Folder);
        logFolderBtn.setToolTipText(I18n.get(project, "mesh.btn.log_folder.tooltip"));
        logFolderBtn.addActionListener(e -> SessionLogManager.getInstance(project).openLogFolder());

        actionRow.add(scanBtn);
        actionRow.add(pasteBtn);
        actionRow.add(logFolderBtn);
        actionRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        center.add(iconLabel);
        center.add(Box.createVerticalStrut(10));
        center.add(titleLabel);
        center.add(Box.createVerticalStrut(6));
        center.add(subLabel);
        center.add(Box.createVerticalStrut(16));
        center.add(actionRow);

        emptyStatePanel.add(center, BorderLayout.CENTER);
    }

    private void initListeners() {
        // Table row selection
        errorTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = errorTable.getSelectedRow();
                if (row >= 0) {
                    MeshRequestEntry entry = errorTableModel.getEntryAt(row);
                    detailPanel.setEntry(entry);
                } else {
                    detailPanel.setEntry(null);
                }
            }
        });

        // Search text
        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                errorTableModel.setSearchText(searchField.getText());
                checkEmptyState();
            }
        });

        // Error Type Combo
        errorTypeCombo.addActionListener(e -> {
            String selected = (String) errorTypeCombo.getSelectedItem();
            applyCategoryFilter(selected);
        });

        // Chip button listeners
        chipAll.addActionListener(e -> applyCategoryFilter("All Errors"));
        chipGql.addActionListener(e -> applyCategoryFilter("GraphQL Server Error"));
        chipValidation.addActionListener(e -> applyCategoryFilter("GraphQL Validation Error"));
        chipHttp5xx.addActionListener(e -> applyCategoryFilter("HTTP 5xx (Server)"));
        chipHttp4xx.addActionListener(e -> applyCategoryFilter("HTTP 4xx (Client)"));
        chipTimeout.addActionListener(e -> applyCategoryFilter("Network Timeout"));
        chipApp.addActionListener(e -> applyCategoryFilter("App Exception"));

        captureService.addListener(this);
    }

    private void applyCategoryFilter(@Nullable String category) {
        if ("GraphQL Server Error".equals(category)) {
            errorTableModel.setErrorTypeFilter(MeshErrorType.GRAPHQL_SERVER_ERROR);
            errorTypeCombo.setSelectedItem("GraphQL Server Error");
        } else if ("GraphQL Validation Error".equals(category)) {
            errorTableModel.setErrorTypeFilter(MeshErrorType.GRAPHQL_VALIDATION_ERROR);
            errorTypeCombo.setSelectedItem("GraphQL Validation Error");
        } else if ("HTTP 5xx (Server)".equals(category)) {
            errorTableModel.setErrorTypeFilter(MeshErrorType.HTTP_5XX);
            errorTypeCombo.setSelectedItem("HTTP 5xx (Server)");
        } else if ("HTTP 4xx (Client)".equals(category)) {
            errorTableModel.setErrorTypeFilter(MeshErrorType.HTTP_4XX);
            errorTypeCombo.setSelectedItem("HTTP 4xx (Client)");
        } else if ("Network Timeout".equals(category)) {
            errorTableModel.setErrorTypeFilter(MeshErrorType.NETWORK_TIMEOUT);
            errorTypeCombo.setSelectedItem("Network Timeout");
        } else if ("App Exception".equals(category)) {
            errorTableModel.setErrorTypeFilter(MeshErrorType.APP_EXCEPTION);
            errorTypeCombo.setSelectedItem("App Exception");
        } else {
            errorTableModel.setErrorTypeFilter(null);
            errorTypeCombo.setSelectedItem("All Errors");
        }
        checkEmptyState();
    }

    private void reloadEntries() {
        List<MeshRequestEntry> all = captureService.getEntries();
        errorTableModel.setEntries(all);
        updateMetrics();
        checkEmptyState();
    }

    private void updateMetrics() {
        int totalErr = errorTableModel.getErrorCount();
        int gqlErr = 0;
        int valErr = 0;
        int h5xx = 0;
        int h4xx = 0;
        int timeouts = 0;
        int appExcs = 0;

        for (MeshRequestEntry e : captureService.getEntries()) {
            if (e.hasError()) {
                if (e.getErrorType() == MeshErrorType.GRAPHQL_SERVER_ERROR) gqlErr++;
                else if (e.getErrorType() == MeshErrorType.GRAPHQL_VALIDATION_ERROR) valErr++;
                else if (e.getErrorType() == MeshErrorType.HTTP_5XX) h5xx++;
                else if (e.getErrorType() == MeshErrorType.HTTP_4XX) h4xx++;
                else if (e.getErrorType() == MeshErrorType.NETWORK_TIMEOUT) timeouts++;
                else if (e.getErrorType() == MeshErrorType.APP_EXCEPTION) appExcs++;
            }
        }

        chipAll.setText(I18n.get(project, "mesh.error.chip_all") + " (" + totalErr + ")");
        chipGql.setText(I18n.get(project, "mesh.error.chip_gql") + " (" + gqlErr + ")");
        chipValidation.setText(I18n.get(project, "mesh.error.chip_validation") + " (" + valErr + ")");
        chipHttp5xx.setText(I18n.get(project, "mesh.error.chip_http5xx") + " (" + h5xx + ")");
        chipHttp4xx.setText(I18n.get(project, "mesh.error.chip_http4xx") + " (" + h4xx + ")");
        chipTimeout.setText(I18n.get(project, "mesh.error.chip_timeout") + " (" + timeouts + ")");
        chipApp.setText(I18n.get(project, "mesh.error.chip_app") + " (" + appExcs + ")");
    }

    private void checkEmptyState() {
        if (errorTableModel.getRowCount() == 0) {
            centerCardLayout.show(centerPanel, "EMPTY");
        } else {
            centerCardLayout.show(centerPanel, "CONTENT");
        }
    }

    private void exportFullErrorReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Session Error Summary Report\n\n");
        sb.append("**Total Errors Captured**: ").append(errorTableModel.getErrorCount()).append("\n\n");

        List<MeshRequestEntry> errors = new ArrayList<>();
        for (MeshRequestEntry e : captureService.getEntries()) {
            if (e.hasError()) errors.add(e);
        }

        if (errors.isEmpty()) {
            sb.append("No errors captured in this session.\n");
        } else {
            for (MeshRequestEntry err : errors) {
                sb.append(MeshDetailPanel.generateErrorReport(err)).append("\n---\n\n");
            }
        }

        CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
        ConfigNotifier.notifyInfo(project, "Complete error diagnostic report copied to clipboard (" + errors.size() + " errors).");
    }

    private static JButton createMetricChip(String text, JBColor color) {
        JButton btn = new JButton(text);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 11f));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        return btn;
    }

    @Override
    public void onEntryAdded(@NotNull MeshRequestEntry entry) {
        errorTableModel.addEntry(entry);
        updateMetrics();
        checkEmptyState();
    }

    @Override
    public void onEntryUpdated(@NotNull MeshRequestEntry entry) {
        errorTableModel.updateEntry(entry);
        updateMetrics();
        checkEmptyState();
        int row = errorTable.getSelectedRow();
        if (row >= 0) {
            MeshRequestEntry current = errorTableModel.getEntryAt(row);
            if (current != null && current.getId() == entry.getId()) {
                detailPanel.setEntry(entry);
            }
        }
    }

    @Override
    public void onCleared() {
        errorTableModel.clear();
        detailPanel.setEntry(null);
        updateMetrics();
        checkEmptyState();
    }

    @Override
    public void dispose() {
        captureService.removeListener(this);
    }

    public @Nullable Integer getSelectedEntryId() {
        int row = errorTable.getSelectedRow();
        if (row >= 0 && row < errorTableModel.getRowCount()) {
            MeshRequestEntry e = errorTableModel.getEntryAt(row);
            return e != null ? e.getId() : null;
        }
        return null;
    }

    public void selectEntryById(@Nullable Integer id) {
        if (id == null) return;
        for (int i = 0; i < errorTableModel.getRowCount(); i++) {
            MeshRequestEntry e = errorTableModel.getEntryAt(i);
            if (e != null && e.getId() == id) {
                errorTable.setRowSelectionInterval(i, i);
                errorTable.scrollRectToVisible(errorTable.getCellRect(i, 0, true));
                detailPanel.setEntry(e);
                break;
            }
        }
    }
}
