package configswitcher.mesh.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.mesh.model.MeshOperationType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import configswitcher.mesh.service.MeshCaptureService;
import configswitcher.ui.HelpLabel;
import com.intellij.openapi.Disposable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.util.List;

public class MeshInspectorPanel extends JPanel implements MeshCaptureService.MeshCaptureListener, Disposable {

    private final Project project;
    private final MeshCaptureService captureService;
    private final MeshRequestTableModel tableModel;
    private final JBTable table;
    private final MeshDetailPanel detailPanel;

    private final SearchTextField searchField = new SearchTextField();
    private final ComboBox<String> levelFilterCombo = new ComboBox<>(new String[]{"All Levels", "ERROR", "WARN", "INFO", "DEBUG"});
    private final ComboBox<String> typeFilterCombo = new ComboBox<>(new String[]{"All Types", "QUERY", "MUTATION"});
    private final ComboBox<String> statusFilterCombo = new ComboBox<>(new String[]{
            "All Requests",
            "OK",
            "All Errors",
            "GraphQL Server Error",
            "GraphQL Validation Error",
            "HTTP 5xx (Server Error)",
            "HTTP 4xx (Client Error)",
            "Network Timeout",
            "App Exception",
            "Pending"
    });
    private final JBCheckBox autoScrollCheckBox = new JBCheckBox("Auto-scroll", true);
    private final JButton pauseResumeBtn = new JButton("Pause", AllIcons.Actions.Pause);
    private final JButton errorsOnlyToggleBtn = new JButton("Errors (0)", AllIcons.General.Error);
    private static final JBColor MESH_BLUE_COLOR = new JBColor(new java.awt.Color(0, 102, 204), new java.awt.Color(88, 157, 246));
    private final JButton meshOnlyToggleBtn = new JButton("MESH (0)", AllIcons.General.Web);
    private final JBLabel summaryLabel = new JBLabel("Total: 0 | Success: 0 | Errors: 0 | Avg Latency: 0 ms");
    private boolean errorsOnlyActive = false;
    private boolean meshOnlyActive = false;

    public MeshInspectorPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.captureService = MeshCaptureService.getInstance(project);
        this.tableModel = new MeshRequestTableModel(project);
        this.table = new JBTable(tableModel);
        this.detailPanel = new MeshDetailPanel(project);

        initUI();
        initListeners();

        // Load existing entries or auto-scan session.log
        List<MeshRequestEntry> existing = captureService.getEntries();
        if (existing.isEmpty()) {
            configswitcher.service.SessionLogManager.getInstance(project).scanSessionLogInAnalyzer();
            existing = captureService.getEntries();
        }
        tableModel.setEntries(existing);
        updateSummary();
    }

    private void initUI() {
        // Top Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        searchField.getTextEditor().getEmptyText().setText(I18n.get(project, "mesh.search.placeholder"));
        searchField.getTextEditor().setColumns(20);
        toolbar.add(searchField);

        levelFilterCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("All Levels".equals(value)) {
                    setText(I18n.get(project, "mesh.filter.all_levels"));
                }
                return c;
            }
        });
        toolbar.add(new JBLabel(I18n.get(project, "mesh.label.level")));
        toolbar.add(levelFilterCombo);

        typeFilterCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("All Types".equals(value)) {
                    setText(I18n.get(project, "mesh.filter.all_types"));
                }
                return c;
            }
        });
        toolbar.add(new JBLabel(I18n.get(project, "mesh.label.type")));
        toolbar.add(typeFilterCombo);

        statusFilterCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value != null) {
                    String s = value.toString();
                    if ("All Requests".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.all_requests"));
                        setIcon(null);
                    } else if ("OK".equals(s) || "Success (OK)".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.ok"));
                        setIcon(MeshRequestStatus.SUCCESS.getIcon());
                    } else if ("All Errors".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.all_errors"));
                        setIcon(AllIcons.General.Error);
                    } else if ("GraphQL Server Error".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.gql_server"));
                        setIcon(AllIcons.General.Error);
                    } else if ("GraphQL Validation Error".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.gql_validation"));
                        setIcon(AllIcons.General.Warning);
                    } else if ("HTTP 5xx (Server Error)".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.http5xx"));
                        setIcon(AllIcons.General.Error);
                    } else if ("HTTP 4xx (Client Error)".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.http4xx"));
                        setIcon(AllIcons.General.Warning);
                    } else if ("Network Timeout".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.timeout"));
                        setIcon(AllIcons.General.Warning);
                    } else if ("App Exception".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.app_exception"));
                        setIcon(AllIcons.General.Error);
                    } else if ("Pending".equals(s)) {
                        setText(I18n.get(project, "mesh.filter.pending"));
                        setIcon(MeshRequestStatus.PENDING.getIcon());
                    }
                }
                return c;
            }
        });
        toolbar.add(new JBLabel(I18n.get(project, "mesh.label.status")));
        toolbar.add(statusFilterCombo);
        toolbar.add(new HelpLabel(I18n.get(project, "help.mesh.inspector_filters")));

        errorsOnlyToggleBtn.setToolTipText(I18n.get(project, "mesh.btn.errors_only.tooltip"));
        errorsOnlyToggleBtn.addActionListener(e -> {
            errorsOnlyActive = !errorsOnlyActive;
            tableModel.setOnlyErrorsFilter(errorsOnlyActive);
            errorsOnlyToggleBtn.setIcon(errorsOnlyActive ? AllIcons.Actions.CheckMulticaret : AllIcons.General.Error);
            if (errorsOnlyActive) {
                statusFilterCombo.setSelectedItem("All Errors");
            } else {
                statusFilterCombo.setSelectedItem("All Requests");
            }
            updateSummary();
        });
        toolbar.add(errorsOnlyToggleBtn);

        meshOnlyToggleBtn.setFont(meshOnlyToggleBtn.getFont().deriveFont(java.awt.Font.BOLD));
        meshOnlyToggleBtn.setForeground(MESH_BLUE_COLOR);
        meshOnlyToggleBtn.setToolTipText(I18n.get(project, "mesh.btn.mesh_only.tooltip"));
        meshOnlyToggleBtn.addActionListener(e -> {
            meshOnlyActive = !meshOnlyActive;
            tableModel.setOnlyMeshFilter(meshOnlyActive);
            meshOnlyToggleBtn.setIcon(meshOnlyActive ? AllIcons.Actions.CheckMulticaret : AllIcons.General.Web);
            updateSummary();
        });
        toolbar.add(meshOnlyToggleBtn);

        JButton scanLogBtn = new JButton(I18n.get(project, "mesh.btn.scan_log"), AllIcons.Actions.Find);
        scanLogBtn.setToolTipText(I18n.get(project, "mesh.btn.scan_log.tooltip"));
        scanLogBtn.addActionListener(e -> {
            int lines = configswitcher.service.SessionLogManager.getInstance(project).scanSessionLogInAnalyzer();
            if (lines > 0) {
                configswitcher.util.ConfigNotifier.notifyInfo(project, "Scanned " + lines + " lines from session.log.");
            } else {
                configswitcher.util.ConfigNotifier.notifyInfo(project, "session.log is empty. Run your application to generate logs.");
            }
        });
        toolbar.add(scanLogBtn);

        JButton pasteLogBtn = new JButton(I18n.get(project, "mesh.btn.paste_log"), AllIcons.Actions.MenuPaste);
        pasteLogBtn.setToolTipText(I18n.get(project, "mesh.btn.paste_log.tooltip"));
        pasteLogBtn.addActionListener(e -> new PasteLogDialog(project).show());
        toolbar.add(pasteLogBtn);

        JButton clearBtn = new JButton(I18n.get(project, "mesh.btn.clear"), AllIcons.Actions.GC);
        clearBtn.setToolTipText(I18n.get(project, "mesh.btn.clear.tooltip"));
        clearBtn.addActionListener(e -> captureService.clear());
        toolbar.add(clearBtn);

        pauseResumeBtn.setText(I18n.get(project, "mesh.btn.pause"));
        pauseResumeBtn.setToolTipText(I18n.get(project, "mesh.btn.pause.tooltip"));
        pauseResumeBtn.addActionListener(e -> {
            boolean paused = !captureService.isPaused();
            captureService.setPaused(paused);
            pauseResumeBtn.setText(paused ? I18n.get(project, "mesh.btn.resume") : I18n.get(project, "mesh.btn.pause"));
            pauseResumeBtn.setToolTipText(paused ? I18n.get(project, "mesh.btn.resume.tooltip") : I18n.get(project, "mesh.btn.pause.tooltip"));
            pauseResumeBtn.setIcon(paused ? AllIcons.Actions.Resume : AllIcons.Actions.Pause);
        });
        toolbar.add(pauseResumeBtn);

        autoScrollCheckBox.setText(I18n.get(project, "mesh.chk.auto_scroll"));
        toolbar.add(autoScrollCheckBox);

        JButton exportBtn = new JButton(I18n.get(project, "mesh.btn.export_json"), AllIcons.ToolbarDecorator.Export);
        exportBtn.setToolTipText(I18n.get(project, "mesh.btn.export_json.tooltip"));
        exportBtn.addActionListener(e -> {
            String json = captureService.exportToJson();
            CopyPasteManager.getInstance().setContents(new StringSelection(json));
        });
        toolbar.add(exportBtn);

        JButton openLogBtn = new JButton(I18n.get(project, "mesh.btn.log_folder"), AllIcons.Nodes.Folder);
        openLogBtn.setToolTipText(I18n.get(project, "mesh.btn.log_folder.tooltip"));
        openLogBtn.addActionListener(e -> {
            configswitcher.service.SessionLogManager.getInstance(project).openLogFolder();
        });
        toolbar.add(openLogBtn);
        toolbar.add(new HelpLabel(I18n.get(project, "help.mesh.inspector_actions")));

        add(toolbar, BorderLayout.NORTH);

        // Table setup
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_LAST_COLUMN);
        table.setStriped(true);

        // Table column widths
        if (table.getColumnModel().getColumnCount() >= 7) {
            table.getColumnModel().getColumn(0).setPreferredWidth(45);  // #
            table.getColumnModel().getColumn(0).setMaxWidth(60);
            table.getColumnModel().getColumn(1).setPreferredWidth(75);  // Time (HH:mm:ss)
            table.getColumnModel().getColumn(1).setMaxWidth(90);
            table.getColumnModel().getColumn(2).setPreferredWidth(60);  // Level
            table.getColumnModel().getColumn(2).setMaxWidth(75);
            table.getColumnModel().getColumn(3).setPreferredWidth(95);  // Status & Error badge
            table.getColumnModel().getColumn(3).setMaxWidth(115);
            table.getColumnModel().getColumn(4).setPreferredWidth(80);  // HTTP
            table.getColumnModel().getColumn(4).setMaxWidth(100);
            table.getColumnModel().getColumn(5).setPreferredWidth(75);  // Type
            table.getColumnModel().getColumn(5).setMaxWidth(90);
            table.getColumnModel().getColumn(6).setPreferredWidth(220); // Operation
        }

        // Custom Cell Renderer for Level column
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable t, Object val, boolean isSelected, boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, isSelected, hasFocus, row, col);
                if (val instanceof String lvl) {
                    setText(lvl);
                    if (!isSelected) {
                        switch (lvl.toUpperCase()) {
                            case "ERROR" -> setForeground(JBColor.RED);
                            case "WARN", "WARNING" -> setForeground(new JBColor(new java.awt.Color(204, 102, 0), new java.awt.Color(245, 166, 35)));
                            case "DEBUG", "TRACE" -> setForeground(JBColor.GRAY);
                            default -> setForeground(JBColor.foreground());
                        }
                    }
                }
                return c;
            }
        });

        // Custom Cell Renderer for Status badge
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable t, Object val, boolean isSelected, boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, isSelected, hasFocus, row, col);
                if (val instanceof MeshRequestEntry entry) {
                    if (entry.hasError()) {
                        configswitcher.mesh.model.MeshErrorType errType = entry.getErrorType();
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
                                setForeground(MeshRequestStatus.ERROR.getColor());
                            }
                        }
                        String tooltip = entry.getRootCauseMessage() != null ? entry.getRootCauseMessage() : entry.getErrorMessage();
                        setToolTipText(tooltip != null ? tooltip : "Error occurred");
                    } else if (entry.getStatus() == MeshRequestStatus.SUCCESS) {
                        setText("OK");
                        setIcon(MeshRequestStatus.SUCCESS.getIcon());
                        if (!isSelected) {
                            setForeground(MeshRequestStatus.SUCCESS.getColor());
                        }
                        setToolTipText("Request succeeded");
                    } else if (entry.getStatus() == MeshRequestStatus.PENDING) {
                        setText("PENDING");
                        setIcon(MeshRequestStatus.PENDING.getIcon());
                        if (!isSelected) {
                            setForeground(MeshRequestStatus.PENDING.getColor());
                        }
                        setToolTipText("Pending response");
                    } else if (entry.getStatus() == MeshRequestStatus.WARN) {
                        setText("WARN");
                        setIcon(MeshRequestStatus.WARN.getIcon());
                        if (!isSelected) {
                            setForeground(MeshRequestStatus.WARN.getColor());
                        }
                        String tooltip = entry.getRootCauseMessage() != null ? entry.getRootCauseMessage() : entry.getErrorMessage();
                        setToolTipText(tooltip != null ? tooltip : "Warning logged");
                    } else {
                        setText(entry.getStatus().getLabel());
                        setIcon(entry.getStatus().getIcon());
                        if (!isSelected) {
                            setForeground(entry.getStatus().getColor());
                        }
                        setToolTipText(null);
                    }
                } else if (val instanceof MeshRequestStatus s) {
                    setText(s.getLabel());
                    setIcon(s.getIcon());
                    if (!isSelected) {
                        setForeground(s.getColor());
                    }
                    setToolTipText(null);
                }
                return c;
            }
        });


        // Splitter
        OnePixelSplitter splitter = new OnePixelSplitter(false, 0.48f);
        splitter.setHonorComponentsMinimumSize(false);
        splitter.setAndLoadSplitterProportionKey("MeshInspector.splitterProportion");
        JBScrollPane tableScrollPane = new JBScrollPane(table);
        tableScrollPane.setMinimumSize(new Dimension(100, 100));
        splitter.setFirstComponent(tableScrollPane);
        splitter.setSecondComponent(detailPanel);
        add(splitter, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        statusBar.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));
        statusBar.add(summaryLabel);
        add(statusBar, BorderLayout.SOUTH);
    }

    private void initListeners() {
        // Table row selection
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    MeshRequestEntry entry = tableModel.getEntryAt(row);
                    detailPanel.setEntry(entry);
                } else {
                    detailPanel.setEntry(null);
                }
            }
        });

        // Search text filter
        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                tableModel.setSearchText(searchField.getText());
                updateSummary();
            }
        });

        // Level filter
        levelFilterCombo.addActionListener(e -> {
            String selected = (String) levelFilterCombo.getSelectedItem();
            tableModel.setLevelFilter(selected);
            updateSummary();
        });

        // Type filter
        typeFilterCombo.addActionListener(e -> {
            String selected = (String) typeFilterCombo.getSelectedItem();
            if ("QUERY".equals(selected)) {
                tableModel.setTypeFilter(MeshOperationType.QUERY);
            } else if ("MUTATION".equals(selected)) {
                tableModel.setTypeFilter(MeshOperationType.MUTATION);
            } else {
                tableModel.setTypeFilter(null);
            }
            updateSummary();
        });

        // Status & Error filter
        statusFilterCombo.addActionListener(e -> {
            String selected = (String) statusFilterCombo.getSelectedItem();
            tableModel.setErrorTypeFilter(null);
            tableModel.setOnlyErrorsFilter(false);
            tableModel.setStatusFilter(null);

            if ("OK".equals(selected) || "Success (OK)".equals(selected)) {
                tableModel.setStatusFilter(MeshRequestStatus.SUCCESS);
            } else if ("All Errors".equals(selected)) {
                tableModel.setOnlyErrorsFilter(true);
            } else if ("GraphQL Server Error".equals(selected)) {
                tableModel.setErrorTypeFilter(configswitcher.mesh.model.MeshErrorType.GRAPHQL_SERVER_ERROR);
            } else if ("GraphQL Validation Error".equals(selected)) {
                tableModel.setErrorTypeFilter(configswitcher.mesh.model.MeshErrorType.GRAPHQL_VALIDATION_ERROR);
            } else if ("HTTP 5xx (Server Error)".equals(selected)) {
                tableModel.setErrorTypeFilter(configswitcher.mesh.model.MeshErrorType.HTTP_5XX);
            } else if ("HTTP 4xx (Client Error)".equals(selected)) {
                tableModel.setErrorTypeFilter(configswitcher.mesh.model.MeshErrorType.HTTP_4XX);
            } else if ("Network Timeout".equals(selected)) {
                tableModel.setErrorTypeFilter(configswitcher.mesh.model.MeshErrorType.NETWORK_TIMEOUT);
            } else if ("App Exception".equals(selected)) {
                tableModel.setErrorTypeFilter(configswitcher.mesh.model.MeshErrorType.APP_EXCEPTION);
            } else if ("Pending".equals(selected)) {
                tableModel.setStatusFilter(MeshRequestStatus.PENDING);
            }
            updateSummary();
        });

        captureService.addListener(this);
    }

    private void updateSummary() {
        int total = tableModel.getTotalCount();
        int ok = tableModel.getSuccessCount();
        int err = tableModel.getErrorCount();
        int gqlErr = tableModel.getGraphQLErrorCount();
        int httpErr = tableModel.getHttpErrorCount();
        int timeout = tableModel.getTimeoutCount();
        double avgLatency = tableModel.getAverageLatencyMs();

        boolean isRu = I18n.get(project, "mesh.btn.clear").equals("Очистить");
        errorsOnlyToggleBtn.setText(isRu ? "Ошибки (" + err + ")" : "Errors (" + err + ")");
        if (err > 0) {
            errorsOnlyToggleBtn.setForeground(JBColor.RED);
        } else {
            errorsOnlyToggleBtn.setForeground(JBColor.GRAY);
        }

        int meshCount = tableModel.getMeshCount();
        meshOnlyToggleBtn.setText("MESH (" + meshCount + ")");
        meshOnlyToggleBtn.setForeground(MESH_BLUE_COLOR);

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.get(project, "mesh.summary.format", total, ok, err));
        if (err > 0) {
            sb.append(I18n.get(project, "mesh.summary.gql_http_timeout", gqlErr, httpErr, timeout));
        }
        sb.append(I18n.get(project, "mesh.summary.avg_latency", String.format("%.0f", avgLatency)));
        summaryLabel.setText(sb.toString());
    }

    @Override
    public void onEntryAdded(@NotNull MeshRequestEntry entry) {
        tableModel.addEntry(entry);
        updateSummary();
        if (autoScrollCheckBox.isSelected() && tableModel.getRowCount() > 0) {
            int lastRow = tableModel.getRowCount() - 1;
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
        }
    }

    @Override
    public void onEntryUpdated(@NotNull MeshRequestEntry entry) {
        tableModel.updateEntry(entry);
        updateSummary();
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
            MeshRequestEntry current = tableModel.getEntryAt(selectedRow);
            if (current != null && current.getId() == entry.getId()) {
                detailPanel.setEntry(entry);
            }
        }
    }

    @Override
    public void onCleared() {
        tableModel.clear();
        detailPanel.setEntry(null);
        updateSummary();
    }

    @Override
    public void dispose() {
        captureService.removeListener(this);
    }

    public @Nullable Integer getSelectedEntryId() {
        int row = table.getSelectedRow();
        if (row >= 0 && row < tableModel.getRowCount()) {
            MeshRequestEntry e = tableModel.getEntryAt(row);
            return e != null ? e.getId() : null;
        }
        return null;
    }

    public void selectEntryById(@Nullable Integer id) {
        if (id == null) return;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            MeshRequestEntry e = tableModel.getEntryAt(i);
            if (e != null && e.getId() == id) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                detailPanel.setEntry(e);
                break;
            }
        }
    }
}
