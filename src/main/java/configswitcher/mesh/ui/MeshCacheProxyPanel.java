package configswitcher.mesh.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.mesh.service.GraphQLFormatter;
import configswitcher.mesh.service.MeshCacheProxy;
import configswitcher.mesh.service.MeshCacheProxy.RequestView;
import configswitcher.state.PluginSettingsState;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;

final class MeshCacheProxyPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Color MISS = new JBColor(new Color(0xa14700), new Color(0xffb369));
    private static final Color BYPASS = new JBColor(new Color(0x245aa3), new Color(0x88bbff));
    private static final Color HIT = new JBColor(new Color(0x216b3b), new Color(0x79d699));
    private final Timer updateTimer;
    private List<RequestView> displayed = List.of();

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
        DefaultListModel<RequestView> model = new DefaultListModel<>();
        JBList<RequestView> list = new JBList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> parent, Object value, int index, boolean selected, boolean focus) {
                String label = value instanceof RequestView row
                        ? TIME.format(row.time()) + "   " + row.operation() + "   " + row.status() + "   " + row.cacheStatus() : "";
                super.getListCellRendererComponent(parent, label, index, selected, focus);
                if (!selected && value instanceof RequestView row) {
                    setForeground(switch (row.cacheStatus()) {
                        case "MISS" -> MISS;
                        case "BYPASS" -> BYPASS;
                        case "HIT" -> HIT;
                        default -> parent.getForeground();
                    });
                }
                return this;
            }
        });
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
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            RequestView row = list.getSelectedValue();
            request.setText(row == null ? "" : formatRequest(row.request()));
            response.setText(row == null ? "" : prettyJson(row.response()));
            summary.setText(row == null ? "" : I18n.get(project, "mesh.proxy.detail_format").formatted(
                    row.operation(), row.status(), row.elapsedMs(), row.cacheStatus(), row.lookup(), row.storage(),
                    row.requestHeaders(), row.responseHeaders()));
            summary.setCaretPosition(0);
            request.setCaretPosition(0);
            response.setCaretPosition(0);
            refresh.setEnabled(enabled.isSelected() && row != null && list.getSelectedIndices().length == 1
                    && row.refreshable() && list.isEnabled());
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
            list.getSelectedValuesList().forEach(row -> saved.put(row.ruleId(), row.operation()));
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
            if (rows.equals(displayed)) return;
            List<RequestView> selected = list.getSelectedValuesList();
            displayed = rows;
            list.setValueIsAdjusting(true);
            model.clear();
            model.addAll(rows);
            int[] indices = IntStream.range(0, rows.size()).filter(i -> selected.contains(rows.get(i))).toArray();
            if (indices.length != 0) list.setSelectedIndices(indices);
            else if (!rows.isEmpty()) list.setSelectedIndex(0);
            list.setValueIsAdjusting(false);
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
            RequestView selected = list.getSelectedValue();
            if (selected == null || !selected.refreshable()) return;
            list.setEnabled(false);
            refresh.setEnabled(false);
            status.setText(I18n.get(project, "mesh.proxy.refreshing"));
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                RequestView refreshed = null;
                try {
                    refreshed = proxy.refresh(selected);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    // The failed refresh appears in the request list.
                }
                RequestView result = refreshed;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    list.setEnabled(true);
                    update.run();
                    if (enabled.isSelected()) {
                        status.setText(I18n.get(project, result != null ? "mesh.proxy.refreshed" : "mesh.proxy.refresh_failed"));
                        if (result != null) list.setSelectedValue(result, true);
                    }
                    RequestView row = list.getSelectedValue();
                    refresh.setEnabled(enabled.isSelected() && row != null && list.getSelectedIndices().length == 1 && row.refreshable());
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
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(list), detail);
        split.setResizeWeight(0.25);
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
