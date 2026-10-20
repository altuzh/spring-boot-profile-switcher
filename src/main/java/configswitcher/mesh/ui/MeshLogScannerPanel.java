package configswitcher.mesh.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.service.SessionLogManager;
import configswitcher.ui.HelpLabel;
import configswitcher.util.ConfigNotifier;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Log Scanner & File Inspector tab.
 * Allows viewing, searching, and feeding session.log and external log streams
 * directly into the GraphQL Mesh error classification engine.
 */
public class MeshLogScannerPanel extends JPanel {

    private final Project project;
    private final JTextArea logTextArea = new JTextArea();
    private final SearchTextField searchField = new SearchTextField();
    private final ComboBox<String> levelFilterCombo = new ComboBox<>(new String[]{"ALL", "ERROR", "WARN", "INFO", "DEBUG"});
    private final JBLabel fileInfoLabel = new JBLabel("Log file: -");

    private final List<String> allLines = new ArrayList<>();
    private boolean showingPrev = false;

    public MeshLogScannerPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        initUI();
        loadSessionLog(false);
    }

    private void initUI() {
        // Toolbar
        JPanel topPanel = new JPanel(new BorderLayout());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        searchField.getTextEditor().getEmptyText().setText(I18n.get(project, "mesh.log.search_hint"));
        searchField.getTextEditor().setColumns(22);
        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                applyLogFilter();
            }
        });
        toolbar.add(searchField);

        toolbar.add(new JBLabel(I18n.get(project, "mesh.label.level")));
        levelFilterCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("ALL".equals(value)) {
                    setText(I18n.get(project, "mesh.filter.all_levels"));
                }
                return c;
            }
        });
        levelFilterCombo.addActionListener(e -> applyLogFilter());
        toolbar.add(levelFilterCombo);

        JButton scanIntoAnalyzerBtn = new JButton(I18n.get(project, "mesh.btn.analyze_in_studio"), AllIcons.Actions.Find);
        scanIntoAnalyzerBtn.setToolTipText(I18n.get(project, "mesh.btn.analyze_in_studio.tooltip"));
        scanIntoAnalyzerBtn.addActionListener(e -> {
            int lines = SessionLogManager.getInstance(project).scanSessionLogInAnalyzer();
            ConfigNotifier.notifyInfo(project, "Analyzed " + lines + " lines into Error Diagnostic Studio.");
        });
        toolbar.add(scanIntoAnalyzerBtn);

        JButton refreshBtn = new JButton(I18n.get(project, "mesh.btn.refresh"), AllIcons.Actions.Refresh);
        refreshBtn.setToolTipText(I18n.get(project, "mesh.btn.refresh.tooltip"));
        refreshBtn.addActionListener(e -> loadSessionLog(showingPrev));
        toolbar.add(refreshBtn);

        JButton prevLogBtn = new JButton(I18n.get(project, "mesh.btn.previous_log"), AllIcons.Actions.Back);
        prevLogBtn.setToolTipText(I18n.get(project, "mesh.btn.previous_log.tooltip"));
        prevLogBtn.addActionListener(e -> {
            showingPrev = !showingPrev;
            prevLogBtn.setText(showingPrev ? I18n.get(project, "mesh.btn.current_log") : I18n.get(project, "mesh.btn.previous_log"));
            loadSessionLog(showingPrev);
        });
        toolbar.add(prevLogBtn);

        JButton pasteBtn = new JButton(I18n.get(project, "mesh.btn.paste_logs"), AllIcons.Actions.MenuPaste);
        pasteBtn.setToolTipText(I18n.get(project, "mesh.btn.paste_logs.tooltip"));
        pasteBtn.addActionListener(e -> new PasteLogDialog(project).show());
        toolbar.add(pasteBtn);

        JButton copyAllBtn = new JButton(I18n.get(project, "mesh.btn.copy_all"), AllIcons.Actions.Copy);
        copyAllBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_all.tooltip"));
        copyAllBtn.addActionListener(e -> {
            CopyPasteManager.getInstance().setContents(new StringSelection(logTextArea.getText()));
        });
        toolbar.add(copyAllBtn);

        JButton folderBtn = new JButton(I18n.get(project, "mesh.btn.log_folder"), AllIcons.Nodes.Folder);
        folderBtn.setToolTipText(I18n.get(project, "mesh.btn.log_folder.tooltip"));
        folderBtn.addActionListener(e -> SessionLogManager.getInstance(project).openLogFolder());
        toolbar.add(folderBtn);

        toolbar.add(new HelpLabel(I18n.get(project, "help.mesh.log_scanner")));

        topPanel.add(toolbar, BorderLayout.NORTH);

        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        infoBar.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0));
        fileInfoLabel.setFont(fileInfoLabel.getFont().deriveFont(Font.PLAIN, 11f));
        fileInfoLabel.setForeground(JBColor.GRAY);
        fileInfoLabel.setText(I18n.get(project, "mesh.log.file_info_empty"));
        infoBar.add(fileInfoLabel);
        topPanel.add(infoBar, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);

        // Text area
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logTextArea.setBorder(JBUI.Borders.empty(4));
        add(new JBScrollPane(logTextArea), BorderLayout.CENTER);
    }

    public void loadSessionLog(boolean previous) {
        this.showingPrev = previous;
        SessionLogManager logManager = SessionLogManager.getInstance(project);
        File file = previous ? logManager.getPreviousSessionLogFile() : logManager.getSessionLogFile();

        allLines.clear();
        if (file.exists() && file.length() > 0) {
            long sizeKb = file.length() / 1024;
            fileInfoLabel.setText(String.format("File: %s (%d KB, %d lines)", file.getName(), sizeKb, countLines(file)));
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    allLines.add(line);
                }
            } catch (Throwable t) {
                allLines.add("Error reading log file: " + t.getMessage());
            }
        } else {
            fileInfoLabel.setText(String.format("File: %s (Empty / Not yet created)", file.getName()));
            allLines.add("--- " + file.getName() + " is currently empty or does not exist yet. Run your Spring Boot application to stream logs here. ---");
        }

        applyLogFilter();
    }

    private void applyLogFilter() {
        String filter = searchField.getText().trim().toLowerCase();
        String selectedLevel = (String) levelFilterCombo.getSelectedItem();
        if (selectedLevel == null) selectedLevel = "ALL";

        StringBuilder sb = new StringBuilder();
        boolean previousLineMatched = false;

        for (String line : allLines) {
            if (!"ALL".equalsIgnoreCase(selectedLevel)) {
                configswitcher.util.TerminalOutputFilter.LogLevel detected = configswitcher.util.TerminalOutputFilter.detectLogLevel(line);
                if (detected != configswitcher.util.TerminalOutputFilter.LogLevel.NONE) {
                    if (!detected.name().equalsIgnoreCase(selectedLevel)) {
                        previousLineMatched = false;
                        continue;
                    }
                    previousLineMatched = true;
                } else {
                    // Non-header line: include if previous line was a matching log header (e.g. stack trace continuation)
                    if (!previousLineMatched) {
                        continue;
                    }
                }
            }
            if (!filter.isEmpty() && !line.toLowerCase().contains(filter)) {
                continue;
            }
            sb.append(line).append("\n");
        }

        logTextArea.setText(sb.toString());
        logTextArea.setCaretPosition(0);
    }

    private static int countLines(File file) {
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) lines++;
        } catch (Throwable ignored) {}
        return lines;
    }
}
