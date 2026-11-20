package configswitcher.mesh.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.mesh.service.GraphQLFormatter;
import configswitcher.mesh.service.MeshCacheProxy;
import configswitcher.mesh.service.MeshCacheProxy.RequestView;
import configswitcher.state.PluginSettingsState;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

final class MeshCacheProxyPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Color MISS = new JBColor(new Color(0xa14700), new Color(0xffb369));
    private static final Color BYPASS = new JBColor(new Color(0x245aa3), new Color(0x88bbff));
    private static final Color HIT = new JBColor(new Color(0x216b3b), new Color(0x79d699));
    private final Timer updateTimer;

    MeshCacheProxyPanel(Project project) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));
        MeshCacheProxy proxy = project.getService(MeshCacheProxy.class);
        var settings = PluginSettingsState.getInstance(project).getState();
        JBCheckBox enabled = new JBCheckBox(I18n.get(project, "mesh.proxy.enable"), settings.enableMeshCacheProxy);
        JBLabel status = new JBLabel();
        JBLabel activeProfile = new JBLabel();
        JButton refresh = new JButton(I18n.get(project, "mesh.proxy.refresh"));
        JButton addBypass = new JButton(I18n.get(project, "mesh.proxy.always_bypass"));
        JButton clear = new JButton(I18n.get(project, "mesh.proxy.clear_cache"));
        refresh.setEnabled(false);
        addBypass.setEnabled(false);
        RequestTableModel model = new RequestTableModel(project);
        JBTable table = new JBTable(model) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component cell = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    cell.setForeground(switch (model.requestAt(row).cacheStatus()) {
                        case "MISS" -> MISS;
                        case "BYPASS" -> BYPASS;
                        case "HIT" -> HIT;
                        default -> getForeground();
                    });
                }
                if (cell instanceof JComponent component) {
                    Object value = getValueAt(row, column);
                    component.setToolTipText(value instanceof String text && !text.isEmpty() ? text : null);
                }
                return cell;
            }
        };
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        int[] columnWidths = {110, 240, 65, 80, 50};
        for (int column = 0; column < columnWidths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(JBUI.scale(columnWidths[column]));
        }
        ListSelectionModel selection = table.getSelectionModel();
        JBTextArea summary = new JBTextArea();
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        JBTextArea request = new JBTextArea();
        JBTextArea response = new JBTextArea();
        request.setEditable(false);
        response.setEditable(false);
        JTabbedPane detail = new JTabbedPane();
        detail.addTab(I18n.get(project, "mesh.proxy.details"), new JBScrollPane(summary));
        detail.addTab(I18n.get(project, "mesh.proxy.request"), new JBScrollPane(request));
        detail.addTab(I18n.get(project, "mesh.proxy.response"), new JBScrollPane(response));
        selection.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            RequestView row = model.requestAt(table.getSelectedRow());
            request.setText(row == null ? "" : formatRequest(row.request()));
            response.setText(row == null ? "" : prettyJson(row.response()));
            summary.setText(row == null ? "" : I18n.get(project, "mesh.proxy.detail_format").formatted(
                    row.operation(), TIME.format(row.time()), row.status(), row.elapsedMs(), row.cacheStatus(), row.lookup(), row.storage(),
                    row.requestHeaders(), row.responseHeaders()));
            summary.setCaretPosition(0);
            request.setCaretPosition(0);
            response.setCaretPosition(0);
            refresh.setEnabled(enabled.isSelected() && row != null && table.getSelectedRowCount() == 1
                    && row.refreshable() && table.isEnabled());
            addBypass.setEnabled(row != null);
        });
        DefaultListModel<BypassRule> rules = new DefaultListModel<>();
        JBList<BypassRule> ruleList = new JBList<>(rules);
        ruleList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JButton remove = new JButton(I18n.get(project, "mesh.proxy.remove_bypass"));
        remove.setEnabled(false);
        ruleList.addListSelectionListener(event -> remove.setEnabled(!ruleList.isSelectionEmpty()));
        JPanel rulesPanel = new JPanel(new BorderLayout());
        JPanel rulesToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rulesToolbar.add(remove);
        rulesPanel.add(rulesToolbar, BorderLayout.NORTH);
        rulesPanel.add(new JBScrollPane(ruleList), BorderLayout.CENTER);
        JTabbedPane views = new JTabbedPane();
        Runnable updateRules = () -> {
            rules.clear();
            settings.meshCacheBypassRules.forEach((id, label) -> rules.addElement(new BypassRule(id, label)));
        };
        updateRules.run();
        addBypass.addActionListener(event -> {
            var saved = new LinkedHashMap<>(settings.meshCacheBypassRules);
            Arrays.stream(table.getSelectedRows()).mapToObj(model::requestAt)
                    .forEach(row -> saved.put(row.ruleId(), row.operation()));
            settings.meshCacheBypassRules = saved;
            proxy.setBypassRules(saved.keySet());
            updateRules.run();
            views.setSelectedComponent(rulesPanel);
        });
        remove.addActionListener(event -> {
            var saved = new LinkedHashMap<>(settings.meshCacheBypassRules);
            ruleList.getSelectedValuesList().forEach(rule -> saved.remove(rule.id()));
            settings.meshCacheBypassRules = saved;
            proxy.setBypassRules(saved.keySet());
            updateRules.run();
        });
        Runnable update = () -> {
            clear.setToolTipText(I18n.get(project, "mesh.proxy.clear_profile_tooltip").formatted(settings.selectedProfile));
            String profile = proxy.profile();
            activeProfile.setText(profile == null || !enabled.isSelected() ? ""
                    : I18n.get(project, "mesh.proxy.active_profile").formatted(profile));
            List<RequestView> rows = proxy.requests();
            if (rows.equals(model.rows)) return;
            List<RequestView> selected = Arrays.stream(table.getSelectedRows()).mapToObj(model::requestAt).toList();
            model.setRows(rows);
            selection.setValueIsAdjusting(true);
            for (RequestView requestView : selected) {
                int row = rows.indexOf(requestView);
                if (row >= 0) selection.addSelectionInterval(row, row);
            }
            if (selection.isSelectionEmpty() && !rows.isEmpty()) selection.setSelectionInterval(0, 0);
            selection.setValueIsAdjusting(false);
        };
        enabled.addActionListener(event -> {
            settings.enableMeshCacheProxy = enabled.isSelected();
            if (!enabled.isSelected()) proxy.disable();
            status.setText(I18n.get(project, enabled.isSelected() ? "mesh.proxy.next_launch" : "mesh.proxy.disabled"));
            update.run();
        });
        clear.addActionListener(event -> {
            String profile = settings.selectedProfile;
            clear.setEnabled(false);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                boolean deleted = proxy.clearProfileCache(profile, MeshCacheProxy.projectCacheDirectory(project));
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    clear.setEnabled(true);
                    status.setText(I18n.get(project, deleted ? "mesh.proxy.cache_cleared" : "mesh.proxy.clear_failed")
                            .formatted(profile));
                });
            });
        });
        refresh.addActionListener(event -> {
            RequestView selected = model.requestAt(table.getSelectedRow());
            if (selected == null || !selected.refreshable()) return;
            table.setEnabled(false);
            refresh.setEnabled(false);
            status.setText(I18n.get(project, "mesh.proxy.refreshing"));
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                RequestView refreshed = null;
                try {
                    refreshed = proxy.refresh(selected);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    // The failed refresh appears in the request table.
                }
                RequestView result = refreshed;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    table.setEnabled(true);
                    update.run();
                    if (enabled.isSelected()) {
                        status.setText(I18n.get(project, result != null ? "mesh.proxy.refreshed" : "mesh.proxy.refresh_failed"));
                        int resultRow = result == null ? -1 : model.rows.indexOf(result);
                        if (resultRow >= 0) {
                            selection.setSelectionInterval(resultRow, resultRow);
                            table.scrollRectToVisible(table.getCellRect(resultRow, 0, true));
                        }
                    }
                    RequestView row = model.requestAt(table.getSelectedRow());
                    refresh.setEnabled(enabled.isSelected() && row != null && table.getSelectedRowCount() == 1 && row.refreshable());
                });
            });
        });
        status.setText(I18n.get(project, enabled.isSelected() ? "mesh.proxy.next_launch" : "mesh.proxy.disabled"));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        toolbar.add(enabled);
        toolbar.add(refresh);
        toolbar.add(addBypass);
        toolbar.add(clear);
        toolbar.add(activeProfile);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(table), detail);
        split.setResizeWeight(0.5);
        views.addTab(I18n.get(project, "mesh.proxy.requests"), split);
        views.addTab(I18n.get(project, "mesh.proxy.bypass_rules"), rulesPanel);
        add(toolbar, BorderLayout.NORTH);
        add(views, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        updateTimer = new Timer(600, event -> {
            if (isShowing()) update.run();
        });
        update.run();
    }

    static final class RequestTableModel extends AbstractTableModel {
        private final String[] columns;
        private List<RequestView> rows = List.of();

        RequestTableModel(Project project) {
            columns = new String[] {
                    I18n.get(project, "mesh.table.col.operation"),
                    I18n.get(project, "mesh.proxy.col.root_field"),
                    I18n.get(project, "mesh.proxy.col.cache"),
                    I18n.get(project, "mesh.proxy.col.action"),
                    I18n.get(project, "mesh.table.col.http")
            };
        }

        void setRows(List<RequestView> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        private RequestView requestAt(int row) {
            return row < 0 ? null : rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            RequestView request = rows.get(row);
            boolean refresh = "REFRESH".equals(request.cacheStatus());
            return switch (column) {
                case 0 -> request.operation();
                case 1 -> request.rootField();
                case 2 -> refresh ? "" : request.cacheStatus();
                case 3 -> refresh ? request.cacheStatus() : "";
                case 4 -> request.status();
                default -> throw new IndexOutOfBoundsException(column);
            };
        }
    }

    private record BypassRule(String id, String operation) {
        @Override
        public String toString() {
            return operation + "  (" + id.substring(0, Math.min(8, id.length())) + ")";
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        updateTimer.start();
    }

    @Override
    public void removeNotify() {
        updateTimer.stop();
        super.removeNotify();
    }

    private static String prettyJson(String text) {
        try {
            return text.isBlank() ? text : JSON.toJson(JsonParser.parseString(text));
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static String formatRequest(String text) {
        try {
            var payload = JsonParser.parseString(text).getAsJsonObject();
            var query = payload.remove("query");
            if (query != null && query.isJsonPrimitive() && query.getAsJsonPrimitive().isString()) {
                String formatted = GraphQLFormatter.format(query.getAsString());
                return payload.isEmpty() ? formatted : formatted + "\n\n" + JSON.toJson(payload);
            }
        } catch (RuntimeException ignored) {
        }
        return prettyJson(text);
    }
}
