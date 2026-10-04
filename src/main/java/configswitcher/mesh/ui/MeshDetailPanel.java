package configswitcher.mesh.ui;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.mesh.model.MeshErrorType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import configswitcher.mesh.service.MeshCaptureService;
import configswitcher.state.PluginSettingsState;
import configswitcher.ui.HelpLabel;
import configswitcher.util.ConfigNotifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MeshDetailPanel extends JPanel {

    private final Project project;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardsPanel = new JPanel(cardLayout);
    private final JPanel emptyPanel = new JPanel(new BorderLayout());
    private final JPanel contentPanel = new JPanel(new BorderLayout());

    // Top Bar: Mesh Web Console
    private final ComboBox<String> meshUrlCombo = new ComboBox<>();
    private final JButton addMeshUrlBtn = new JButton(AllIcons.General.Add);
    private final JButton removeMeshUrlBtn = new JButton(AllIcons.General.Remove);
    private final JButton openInMeshBtn = new JButton("Open in Mesh", AllIcons.General.Web);
    private JPanel topBar;
    private boolean isUpdatingCombo = false;

    private final JBTabbedPane tabbedPane = new JBTabbedPane();

    // Tab 0: Error Diagnostic
    private final CardLayout errorCardLayout = new CardLayout();
    private final JPanel errorDiagnosticTab = new JPanel(errorCardLayout);
    private final JPanel errorDetailsPanel = new JPanel(new BorderLayout());
    private final JPanel okStatePanel = new JPanel(new BorderLayout());

    private final JBLabel errorBadgeLabel = new JBLabel();
    private final JBLabel errorHttpStatusLabel = new JBLabel();
    private final JTextArea rootCauseArea = createMonospaceTextArea();
    private final JPanel errorMetaGrid = new JPanel(new GridLayout(0, 2, 8, 4));
    private final DefaultListModel<String> stackTraceListModel = new DefaultListModel<>();
    private final JBList<String> stackTraceList = new JBList<>(stackTraceListModel);
    private final JButton jumpAppBtn = new JButton("Jump to App Source", AllIcons.Actions.Find);
    private final JButton jumpSelectedBtn = new JButton("Jump to Line", AllIcons.Actions.ShowAsTree);
    private final JButton compareDiffBtn = new JButton("Compare with Success", AllIcons.Actions.Diff);

    // Tab 1: Query Tab
    private final JPanel queryTab = new JPanel(new BorderLayout());
    private final JTextArea queryArea = createMonospaceTextArea();
    // Tab 2: Response Tab
    private final JPanel responseTab = new JPanel(new BorderLayout());
    private final JTextArea responseArea = createMonospaceTextArea();
    private final JBLabel responseStatusBanner = new JBLabel();
    // Tab 3: Variables Tab
    private final JPanel varsTab = new JPanel(new BorderLayout());
    private final JTextArea variablesArea = createMonospaceTextArea();
    // Tab 4: Trace Tab
    private final JPanel metaTab = new JPanel(new BorderLayout());
    private final JPanel metadataGrid = new JPanel(new GridLayout(0, 2, 8, 4));
    // Tab 5: Raw Logs Tab
    private final JPanel logsTab = new JPanel(new BorderLayout());
    private final JTextArea rawLogsArea = createMonospaceTextArea();

    private MeshRequestEntry currentEntry = null;

    public MeshDetailPanel(@Nullable Project project) {
        super(new BorderLayout());
        setMinimumSize(new Dimension(0, 0));
        cardsPanel.setMinimumSize(new Dimension(0, 0));
        emptyPanel.setMinimumSize(new Dimension(0, 0));
        contentPanel.setMinimumSize(new Dimension(0, 0));
        tabbedPane.setMinimumSize(new Dimension(0, 0));
        this.project = project;

        // Top bar: Mesh Web Address & Browser Action
        this.topBar = createMeshTopBar();
        topBar.setVisible(false);

        // Empty state: Studio Dashboard
        JPanel emptyCenter = new JPanel();
        emptyCenter.setLayout(new BoxLayout(emptyCenter, BoxLayout.Y_AXIS));
        emptyCenter.setBorder(JBUI.Borders.empty(30, 20));

        JBLabel studioIcon = new JBLabel(AllIcons.Nodes.Folder);
        studioIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel studioTitle = new JBLabel(I18n.get(project, "mesh.empty.title"), JBLabel.CENTER);
        studioTitle.setFont(studioTitle.getFont().deriveFont(Font.BOLD, 15f));
        studioTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel studioSub = new JBLabel(I18n.get(project, "mesh.empty.subtitle"), JBLabel.CENTER);
        studioSub.setForeground(JBColor.GRAY);
        studioSub.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton scanBtn = new JButton(I18n.get(project, "mesh.empty.scan_btn"), AllIcons.Actions.Find);
        scanBtn.setToolTipText(I18n.get(project, "mesh.btn.scan_log.tooltip"));
        scanBtn.addActionListener(e -> {
            int lines = configswitcher.service.SessionLogManager.getInstance(project).scanSessionLogInAnalyzer();
            if (lines > 0) {
                configswitcher.util.ConfigNotifier.notifyInfo(project, "Scanned " + lines + " lines from session.log.");
            } else {
                configswitcher.util.ConfigNotifier.notifyInfo(project, "session.log is empty. Run your application to generate logs.");
            }
        });
        JButton pasteBtn = new JButton(I18n.get(project, "mesh.empty.paste_btn"), AllIcons.Actions.MenuPaste);
        pasteBtn.setToolTipText(I18n.get(project, "mesh.btn.paste_log.tooltip"));
        pasteBtn.addActionListener(e -> new PasteLogDialog(project).show());

        JButton logFolderBtn = new JButton(I18n.get(project, "mesh.empty.folder_btn"), AllIcons.Nodes.Folder);
        logFolderBtn.setToolTipText(I18n.get(project, "mesh.btn.log_folder.tooltip"));
        logFolderBtn.addActionListener(e -> configswitcher.service.SessionLogManager.getInstance(project).openLogFolder());

        btnRow.add(scanBtn);
        btnRow.add(pasteBtn);
        btnRow.add(logFolderBtn);
        btnRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyCenter.add(studioIcon);
        emptyCenter.add(Box.createVerticalStrut(10));
        emptyCenter.add(studioTitle);
        emptyCenter.add(Box.createVerticalStrut(6));
        emptyCenter.add(studioSub);
        emptyCenter.add(Box.createVerticalStrut(14));
        emptyCenter.add(btnRow);

        emptyPanel.add(emptyCenter, BorderLayout.CENTER);

        // Content state
        setupTabs();
        contentPanel.add(topBar, BorderLayout.NORTH);
        contentPanel.add(tabbedPane, BorderLayout.CENTER);

        cardsPanel.add(emptyPanel, "EMPTY");
        cardsPanel.add(contentPanel, "CONTENT");
        add(cardsPanel, BorderLayout.CENTER);

        cardLayout.show(cardsPanel, "EMPTY");
    }

    private void setupTabs() {
        // Error Diagnostic tab is set up here, and added to tabbedPane only when an entry has an error
        setupErrorDiagnosticTab();

        // Tab 1: Query
        queryTab.setMinimumSize(new Dimension(0, 0));
        JPanel queryToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        queryToolbar.setMinimumSize(new Dimension(0, 0));
        JButton copyQueryBtn = new JButton(I18n.get(project, "mesh.btn.copy_query"), AllIcons.Actions.Copy);
        copyQueryBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_query.tooltip"));
        copyQueryBtn.addActionListener(e -> {
            if (currentEntry != null && currentEntry.getFormattedQuery() != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(currentEntry.getFormattedQuery()));
            }
        });
        JButton copyCurlBtn = new JButton(I18n.get(project, "mesh.btn.copy_curl"), AllIcons.Actions.Execute);
        copyCurlBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_curl.tooltip"));
        copyCurlBtn.addActionListener(e -> {
            if (currentEntry != null) {
                String curl = MeshCaptureService.getInstance(project).generateCurlCommand(currentEntry);
                CopyPasteManager.getInstance().setContents(new StringSelection(curl));
            }
        });

        queryToolbar.add(copyQueryBtn);
        queryToolbar.add(copyCurlBtn);
        queryTab.add(queryToolbar, BorderLayout.NORTH);
        queryTab.add(createScrollPane(queryArea), BorderLayout.CENTER);
        tabbedPane.addTab(I18n.get(project, "mesh.detail.tab.query"), AllIcons.Nodes.Folder, queryTab);

        // Tab 2: Response
        responseTab.setMinimumSize(new Dimension(0, 0));
        JPanel responseHeader = new JPanel();
        responseHeader.setMinimumSize(new Dimension(0, 0));
        responseHeader.setLayout(new BoxLayout(responseHeader, BoxLayout.X_AXIS));
        responseHeader.setBorder(JBUI.Borders.empty(4, 8));

        responseStatusBanner.setFont(responseStatusBanner.getFont().deriveFont(Font.BOLD));
        responseHeader.add(responseStatusBanner);
        responseHeader.add(Box.createHorizontalGlue());

        JButton copyResponseBtn = new JButton(I18n.get(project, "mesh.btn.copy_response"), AllIcons.Actions.Copy);
        copyResponseBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_response.tooltip"));
        copyResponseBtn.addActionListener(e -> {
            if (currentEntry != null && currentEntry.getFormattedResponse() != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(currentEntry.getFormattedResponse()));
            }
        });
        responseHeader.add(copyResponseBtn);

        responseTab.add(responseHeader, BorderLayout.NORTH);
        responseTab.add(createScrollPane(responseArea), BorderLayout.CENTER);
        tabbedPane.addTab(I18n.get(project, "mesh.detail.tab.response"), AllIcons.Actions.Preview, responseTab);

        // Tab 3: Variables
        varsTab.setMinimumSize(new Dimension(0, 0));
        JPanel varsToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        varsToolbar.setMinimumSize(new Dimension(0, 0));
        JButton copyVarsBtn = new JButton(I18n.get(project, "mesh.btn.copy_variables"), AllIcons.Actions.Copy);
        copyVarsBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_variables.tooltip"));
        copyVarsBtn.addActionListener(e -> {
            if (currentEntry != null && currentEntry.getFormattedVariables() != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(currentEntry.getFormattedVariables()));
            }
        });
        varsToolbar.add(copyVarsBtn);
        varsTab.add(varsToolbar, BorderLayout.NORTH);
        varsTab.add(createScrollPane(variablesArea), BorderLayout.CENTER);
        tabbedPane.addTab(I18n.get(project, "mesh.detail.tab.variables"), AllIcons.Nodes.Variable, varsTab);

        // Tab 4: Trace & Metadata
        metaTab.setMinimumSize(new Dimension(0, 0));
        metaTab.setBorder(JBUI.Borders.empty(12));
        metadataGrid.setMinimumSize(new Dimension(0, 0));
        metadataGrid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        metaTab.add(createScrollPane(metadataGrid), BorderLayout.CENTER);
        tabbedPane.addTab(I18n.get(project, "mesh.detail.tab.trace"), AllIcons.General.ContextHelp, metaTab);

        // Tab 5: Raw Logs
        logsTab.setMinimumSize(new Dimension(0, 0));
        JPanel logsToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        logsToolbar.setMinimumSize(new Dimension(0, 0));
        JButton copyLogsBtn = new JButton(I18n.get(project, "mesh.btn.copy_logs"), AllIcons.Actions.Copy);
        copyLogsBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_logs.tooltip"));
        copyLogsBtn.addActionListener(e -> {
            if (currentEntry != null) {
                String fullLog = String.join("\n", currentEntry.getRawLogLines());
                CopyPasteManager.getInstance().setContents(new StringSelection(fullLog));
            }
        });
        logsToolbar.add(copyLogsBtn);
        logsTab.add(logsToolbar, BorderLayout.NORTH);
        logsTab.add(createScrollPane(rawLogsArea), BorderLayout.CENTER);
        tabbedPane.addTab(I18n.get(project, "mesh.detail.tab.raw_logs"), AllIcons.Debugger.Console, logsTab);
    }

    private void setupErrorDiagnosticTab() {
        errorDiagnosticTab.setMinimumSize(new Dimension(0, 0));
        okStatePanel.setMinimumSize(new Dimension(0, 0));
        errorDetailsPanel.setMinimumSize(new Dimension(0, 0));

        // --- OK State Panel ---
        JPanel okCenter = new JPanel();
        okCenter.setMinimumSize(new Dimension(0, 0));
        okCenter.setLayout(new BoxLayout(okCenter, BoxLayout.Y_AXIS));
        okCenter.setBorder(JBUI.Borders.empty(40));

        JBLabel okIconLabel = new JBLabel(AllIcons.General.InspectionsOK);
        okIconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JBLabel okTextLabel = new JBLabel(I18n.get(project, "mesh.detail.ok_text"), JBLabel.CENTER);
        okTextLabel.setFont(okTextLabel.getFont().deriveFont(Font.BOLD, 14f));
        okTextLabel.setForeground(MeshRequestStatus.SUCCESS.getColor());
        okTextLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel okSubLabel = new JBLabel(I18n.get(project, "mesh.detail.ok_sub"), JBLabel.CENTER);
        okSubLabel.setForeground(JBColor.GRAY);
        okSubLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        okCenter.add(okIconLabel);
        okCenter.add(Box.createVerticalStrut(10));
        okCenter.add(okTextLabel);
        okCenter.add(Box.createVerticalStrut(6));
        okCenter.add(okSubLabel);
        okStatePanel.add(okCenter, BorderLayout.CENTER);

        // --- Error Details Panel ---
        // Header
        JPanel errorHeader = new JPanel();
        errorHeader.setMinimumSize(new Dimension(0, 0));
        errorHeader.setLayout(new BoxLayout(errorHeader, BoxLayout.Y_AXIS));
        errorHeader.setBorder(JBUI.Borders.empty(8));

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        errorBadgeLabel.setFont(errorBadgeLabel.getFont().deriveFont(Font.BOLD, 13f));
        errorHttpStatusLabel.setFont(errorHttpStatusLabel.getFont().deriveFont(Font.BOLD, 12f));
        errorHttpStatusLabel.setForeground(JBColor.GRAY);
        titleRow.add(errorBadgeLabel);
        titleRow.add(errorHttpStatusLabel);

        // Toolbar
        JPanel actionToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        JButton copyReportBtn = new JButton(I18n.get(project, "mesh.btn.copy_error_report"), AllIcons.Actions.Copy);
        copyReportBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_error_report.tooltip"));
        copyReportBtn.addActionListener(e -> {
            if (currentEntry != null) {
                String report = generateErrorReport(currentEntry);
                CopyPasteManager.getInstance().setContents(new StringSelection(report));
                ConfigNotifier.notifyInfo(project, "Error diagnostic report copied to clipboard.");
            }
        });

        compareDiffBtn.setText(I18n.get(project, "mesh.btn.compare_success"));
        compareDiffBtn.setToolTipText(I18n.get(project, "mesh.btn.compare_success.tooltip"));
        compareDiffBtn.addActionListener(e -> {
            if (currentEntry == null) return;
            MeshRequestEntry prev = MeshCaptureService.getInstance(project).findPreviousSuccessfulEntry(currentEntry);
            if (prev == null) {
                ConfigNotifier.notifyInfo(project, "No previous successful request found for this operation.");
                return;
            }

            String prevText = "/* Request #" + prev.getId() + " (SUCCESS - " + prev.getFormattedTime() + ") */\n\n"
                    + "VARIABLES:\n" + (prev.getFormattedVariables() != null ? prev.getFormattedVariables() : "{}") + "\n\n"
                    + "QUERY:\n" + (prev.getFormattedQuery() != null ? prev.getFormattedQuery() : "");

            String currText = "/* Request #" + currentEntry.getId() + " (" + (currentEntry.getErrorType() != null ? currentEntry.getErrorType().getDisplayName() : "ERROR") + " - " + currentEntry.getFormattedTime() + ") */\n\n"
                    + "VARIABLES:\n" + (currentEntry.getFormattedVariables() != null ? currentEntry.getFormattedVariables() : "{}") + "\n\n"
                    + "QUERY:\n" + (currentEntry.getFormattedQuery() != null ? currentEntry.getFormattedQuery() : "");

            DiffContentFactory contentFactory = DiffContentFactory.getInstance();
            DiffContent prevContent = contentFactory.create(project, prevText);
            DiffContent currContent = contentFactory.create(project, currText);
            SimpleDiffRequest diffReq = new SimpleDiffRequest(
                    "Compare: Success (#" + prev.getId() + ") \u2194 Failed (#" + currentEntry.getId() + ")",
                    prevContent,
                    currContent,
                    "Success (#" + prev.getId() + ")",
                    "Failed (#" + currentEntry.getId() + ")"
            );
            DiffManager.getInstance().showDiff(project, diffReq);
        });

        JButton copyCurlBtn = new JButton(I18n.get(project, "mesh.btn.copy_curl"), AllIcons.Actions.Execute);
        copyCurlBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_curl.tooltip"));
        copyCurlBtn.addActionListener(e -> {
            if (currentEntry != null) {
                String curl = MeshCaptureService.getInstance(project).generateCurlCommand(currentEntry);
                CopyPasteManager.getInstance().setContents(new StringSelection(curl));
            }
        });

        JButton searchWebBtn = new JButton(I18n.get(project, "mesh.btn.search_web"), AllIcons.Actions.Find);
        searchWebBtn.setToolTipText(I18n.get(project, "mesh.btn.search_web.tooltip"));
        searchWebBtn.addActionListener(e -> {
            if (currentEntry != null) {
                String q = currentEntry.getRootCauseMessage() != null ? currentEntry.getRootCauseMessage() : currentEntry.getErrorMessage();
                if (q != null && !q.isBlank()) {
                    String query = q.lines().findFirst().orElse(q);
                    BrowserUtil.browse("https://www.google.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
                }
            }
        });

        actionToolbar.add(copyReportBtn);
        actionToolbar.add(compareDiffBtn);
        actionToolbar.add(copyCurlBtn);
        actionToolbar.add(searchWebBtn);

        // Root cause box
        JPanel rootCausePanel = new JPanel(new BorderLayout());
        rootCausePanel.setBorder(BorderFactory.createTitledBorder(
                JBUI.Borders.customLine(JBColor.RED, 1),
                I18n.get(project, "mesh.detail.root_cause_title"),
                0,
                0,
                null,
                JBColor.RED
        ));
        rootCauseArea.setRows(3);
        rootCauseArea.setLineWrap(true);
        rootCauseArea.setWrapStyleWord(true);
        rootCausePanel.add(createScrollPane(rootCauseArea), BorderLayout.CENTER);

        JPanel rootCauseActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton copyCauseBtn = new JButton(I18n.get(project, "mesh.btn.copy_cause"), AllIcons.Actions.Copy);
        copyCauseBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_cause.tooltip"));
        copyCauseBtn.addActionListener(e -> {
            if (!rootCauseArea.getText().isBlank()) {
                CopyPasteManager.getInstance().setContents(new StringSelection(rootCauseArea.getText()));
            }
        });
        rootCauseActions.add(copyCauseBtn);
        rootCausePanel.add(rootCauseActions, BorderLayout.SOUTH);

        errorHeader.add(titleRow);
        errorHeader.add(Box.createVerticalStrut(4));
        errorHeader.add(actionToolbar);
        errorHeader.add(Box.createVerticalStrut(6));
        errorHeader.add(rootCausePanel);
        errorHeader.add(Box.createVerticalStrut(6));
        errorHeader.add(errorMetaGrid);

        errorDetailsPanel.add(errorHeader, BorderLayout.NORTH);

        // Center: Stack Trace Navigator
        JPanel stackTracePanel = new JPanel(new BorderLayout());
        stackTracePanel.setMinimumSize(new Dimension(0, 0));
        stackTracePanel.setBorder(BorderFactory.createTitledBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                I18n.get(project, "mesh.detail.stack_trace_title")
        ));

        // Stack toolbar
        JPanel stackToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        jumpAppBtn.setText(I18n.get(project, "mesh.btn.jump_app"));
        jumpAppBtn.setToolTipText(I18n.get(project, "mesh.btn.jump_app.tooltip"));
        jumpAppBtn.addActionListener(e -> {
            if (currentEntry == null || currentEntry.getRawStackTrace() == null) return;
            List<SourceNavigator.StackLocation> locs = SourceNavigator.parseAllLocations(currentEntry.getRawStackTrace());
            for (SourceNavigator.StackLocation loc : locs) {
                if (loc.isUserCode()) {
                    boolean success = SourceNavigator.navigateTo(project, loc);
                    if (success) return;
                }
            }
            if (!locs.isEmpty()) {
                SourceNavigator.navigateTo(project, locs.get(0));
            }
        });

        jumpSelectedBtn.setText(I18n.get(project, "mesh.btn.jump_line"));
        jumpSelectedBtn.setToolTipText(I18n.get(project, "mesh.btn.jump_line.tooltip"));
        jumpSelectedBtn.addActionListener(e -> {
            String selected = stackTraceList.getSelectedValue();
            if (selected != null) {
                SourceNavigator.StackLocation loc = SourceNavigator.parseStackLine(selected);
                if (loc != null) {
                    SourceNavigator.navigateTo(project, loc);
                }
            }
        });

        JButton copyStackBtn = new JButton(I18n.get(project, "mesh.btn.copy_stack"), AllIcons.Actions.Copy);
        copyStackBtn.setToolTipText(I18n.get(project, "mesh.btn.copy_stack.tooltip"));
        copyStackBtn.addActionListener(e -> {
            if (currentEntry != null && currentEntry.getRawStackTrace() != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(currentEntry.getRawStackTrace()));
            }
        });

        stackToolbar.setMinimumSize(new Dimension(0, 0));
        stackToolbar.add(jumpAppBtn);
        stackToolbar.add(jumpSelectedBtn);
        stackToolbar.add(copyStackBtn);

        stackTraceList.setMinimumSize(new Dimension(0, 0));
        stackTraceList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stackTraceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stackTraceList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String line = value != null ? value.toString() : "";
                SourceNavigator.StackLocation loc = SourceNavigator.parseStackLine(line);
                if (loc != null) {
                    if (loc.isUserCode()) {
                        setIcon(AllIcons.Nodes.Class);
                        if (!isSelected) {
                            setFont(getFont().deriveFont(Font.BOLD));
                            setForeground(new JBColor(new Color(0, 102, 204), new Color(100, 180, 255)));
                        }
                    } else {
                        setIcon(AllIcons.Nodes.PpLibFolder);
                        if (!isSelected) {
                            setFont(getFont().deriveFont(Font.PLAIN));
                            setForeground(JBColor.GRAY);
                        }
                    }
                } else {
                    setIcon(null);
                    if (line.startsWith("Caused by:") || line.contains("Exception") || line.contains("Error")) {
                        if (!isSelected) {
                            setFont(getFont().deriveFont(Font.BOLD));
                            setForeground(JBColor.RED);
                        }
                    }
                }
                return c;
            }
        });

        stackTraceList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = stackTraceList.getSelectedValue();
                    if (selected != null) {
                        SourceNavigator.StackLocation loc = SourceNavigator.parseStackLine(selected);
                        if (loc != null) {
                            SourceNavigator.navigateTo(project, loc);
                        }
                    }
                }
            }
        });

        stackTracePanel.add(stackToolbar, BorderLayout.NORTH);
        stackTracePanel.add(createScrollPane(stackTraceList), BorderLayout.CENTER);
        errorDetailsPanel.add(stackTracePanel, BorderLayout.CENTER);

        errorDiagnosticTab.add(okStatePanel, "OK");
        errorDiagnosticTab.add(errorDetailsPanel, "ERROR");
    }

    public void setEntry(@Nullable MeshRequestEntry entry) {
        this.currentEntry = entry;
        if (entry == null) {
            if (topBar != null) {
                topBar.setVisible(false);
            }
            cardLayout.show(cardsPanel, "EMPTY");
            return;
        }

        boolean isMesh = entry.isMeshRecord();
        if (topBar != null) {
            topBar.setVisible(isMesh);
        }

        cardLayout.show(cardsPanel, "CONTENT");
        contentPanel.revalidate();
        contentPanel.repaint();

        Component currTab = tabbedPane.getSelectedComponent();

        // Error Diagnostic Tab update: only show diagnostic tab if there is an error to analyze
        if (entry.hasError()) {
            if (tabbedPane.indexOfComponent(errorDiagnosticTab) < 0) {
                tabbedPane.insertTab(I18n.get(project, "mesh.detail.tab.error_active"), AllIcons.General.Error, errorDiagnosticTab, null, 0);
            } else {
                int idx = tabbedPane.indexOfComponent(errorDiagnosticTab);
                tabbedPane.setIconAt(idx, AllIcons.General.Error);
                tabbedPane.setTitleAt(idx, I18n.get(project, "mesh.detail.tab.error_active"));
            }
            errorCardLayout.show(errorDiagnosticTab, "ERROR");

            MeshErrorType errType = entry.getErrorType();
            if (errType != null) {
                errorBadgeLabel.setText("[" + errType.getBadgeText() + "] " + errType.getDisplayName());
                errorBadgeLabel.setIcon(errType.getIcon());
                errorBadgeLabel.setForeground(errType.getColor());
            } else {
                errorBadgeLabel.setText("[ERR] Error Detected");
                errorBadgeLabel.setIcon(AllIcons.General.Error);
                errorBadgeLabel.setForeground(MeshRequestStatus.ERROR.getColor());
            }

            errorHttpStatusLabel.setText("HTTP Status: " + (entry.getHttpStatus() != null ? entry.getHttpStatus() : "500 Internal Server Error"));

            String cause = entry.getRootCauseMessage() != null ? entry.getRootCauseMessage() :
                    (entry.getErrorMessage() != null ? entry.getErrorMessage() : "Unknown error");
            rootCauseArea.setText(cause);
            rootCauseArea.setCaretPosition(0);

            // Populate metadata grid
            errorMetaGrid.removeAll();
            if (entry.getErrorCode() != null) {
                addErrorMetaRow("Error Code", entry.getErrorCode());
            }
            if (entry.getErrorPath() != null) {
                addErrorMetaRow("Error Path", entry.getErrorPath());
            }
            addErrorMetaRow("Operation", entry.getDisplayOperation());
            if (entry.getTraceId() != null) {
                addErrorMetaRow("Trace ID", entry.getTraceId());
            }
            if (entry.getDurationMs() != null) {
                addErrorMetaRow("Latency", entry.getDurationMs() + " ms");
            }
            errorMetaGrid.revalidate();
            errorMetaGrid.repaint();

            // Populate stack trace list
            stackTraceListModel.clear();
            if (entry.getRawStackTrace() != null && !entry.getRawStackTrace().isBlank()) {
                String[] lines = entry.getRawStackTrace().split("\n");
                for (String l : lines) {
                    stackTraceListModel.addElement(l);
                }
                jumpAppBtn.setEnabled(true);
                jumpSelectedBtn.setEnabled(true);
            } else {
                stackTraceListModel.addElement(I18n.get(project, "mesh.detail.no_stack"));
                jumpAppBtn.setEnabled(false);
                jumpSelectedBtn.setEnabled(false);
            }

            // Check if previous successful entry exists for diff button
            MeshRequestEntry prev = project != null ? MeshCaptureService.getInstance(project).findPreviousSuccessfulEntry(entry) : null;
            compareDiffBtn.setEnabled(prev != null);
            compareDiffBtn.setToolTipText(prev != null ?
                    "Compare with Request #" + prev.getId() + " (" + prev.getFormattedTime() + ")" :
                    "No prior successful request found for this operation");

        } else {
            int idx = tabbedPane.indexOfComponent(errorDiagnosticTab);
            if (idx >= 0) {
                tabbedPane.removeTabAt(idx);
            }
        }

        // Query
        queryArea.setText(entry.getFormattedQuery() != null ? entry.getFormattedQuery() : "");
        queryArea.setCaretPosition(0);

        // Response
        responseArea.setText(entry.getFormattedResponse() != null ? entry.getFormattedResponse() : "");
        responseArea.setCaretPosition(0);

        // Response Banner
        if (entry.getStatus() == MeshRequestStatus.SUCCESS) {
            responseStatusBanner.setText("Status: " + (entry.getHttpStatus() != null ? entry.getHttpStatus() : "200 OK") + " (" + entry.getFormattedDuration() + ")");
            responseStatusBanner.setForeground(MeshRequestStatus.SUCCESS.getColor());
            responseStatusBanner.setIcon(AllIcons.General.InspectionsOK);
        } else if (entry.getStatus() == MeshRequestStatus.ERROR) {
            String err = entry.getErrorMessage() != null ? entry.getErrorMessage() : (entry.getHttpStatus() != null ? entry.getHttpStatus() : "Error");
            responseStatusBanner.setText("Error: " + err);
            responseStatusBanner.setForeground(MeshRequestStatus.ERROR.getColor());
            responseStatusBanner.setIcon(AllIcons.General.Error);
        } else if (entry.getStatus() == MeshRequestStatus.PENDING) {
            responseStatusBanner.setText("Status: Pending response...");
            responseStatusBanner.setForeground(MeshRequestStatus.PENDING.getColor());
            responseStatusBanner.setIcon(AllIcons.Process.ProgressPauseSmall);
        } else {
            responseStatusBanner.setText("Status: " + entry.getStatus().getLabel());
            responseStatusBanner.setForeground(entry.getStatus().getColor());
            responseStatusBanner.setIcon(entry.getStatus().getIcon());
        }

        // Variables
        variablesArea.setText(entry.getFormattedVariables() != null ? entry.getFormattedVariables() : (entry.getRawVariables() != null ? entry.getRawVariables() : "{}"));
        variablesArea.setCaretPosition(0);

        // Metadata Grid
        metadataGrid.removeAll();
        addMetaRow("Operation Name", entry.getOperationName() != null ? entry.getOperationName() : "-");
        addMetaRow("Root Field", entry.getRootField() != null ? entry.getRootField() : "-");
        addMetaRow("Operation Type", entry.getOperationType().getDisplayName());
        addMetaRow("Target Endpoint", entry.getEndpoint() != null ? entry.getEndpoint() : "-");
        addMetaRow("HTTP Status", entry.getHttpStatus() != null ? entry.getHttpStatus() : "-");
        addMetaRow("Latency", entry.getFormattedDuration());
        addMetaRow("Response Size", entry.getFormattedSize());
        addMetaRow("Trace ID (x-b3-traceid)", entry.getTraceId() != null ? entry.getTraceId() : "-");
        addMetaRow("Span ID (x-b3-spanid)", entry.getSpanId() != null ? entry.getSpanId() : "-");
        addMetaRow("User ID", entry.getUserId() != null ? entry.getUserId() : "-");
        addMetaRow("Application Name", entry.getAppName() != null ? entry.getAppName() : "-");
        addMetaRow("Thread Name", entry.getThreadName() != null ? entry.getThreadName() : "-");
        addMetaRow("Process PID", entry.getPid() != null ? entry.getPid() : "-");
        addMetaRow("Timestamp", entry.getTimestamp());
        metadataGrid.revalidate();
        metadataGrid.repaint();

        // Raw Logs
        rawLogsArea.setText(String.join("\n", entry.getRawLogLines()));
        rawLogsArea.setCaretPosition(0);

        // Auto-switch tab: Diagnostic tab (0) for errors, Query tab for mesh records
        if (entry.hasError()) {
            tabbedPane.setSelectedComponent(errorDiagnosticTab);
        } else if (entry.isMeshRecord()) {
            if (currTab == null || currTab == errorDiagnosticTab) {
                tabbedPane.setSelectedComponent(queryTab);
            } else if (tabbedPane.indexOfComponent(currTab) >= 0) {
                tabbedPane.setSelectedComponent(currTab);
            } else {
                tabbedPane.setSelectedComponent(queryTab);
            }
        } else {
            if (currTab == logsTab || currTab == metaTab) {
                tabbedPane.setSelectedComponent(currTab);
            } else {
                tabbedPane.setSelectedComponent(logsTab);
            }
        }
    }

    public JBTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    public JPanel getErrorDiagnosticTab() {
        return errorDiagnosticTab;
    }

    private void addErrorMetaRow(String label, String value) {
        JBLabel lbl = new JBLabel(label + ":");
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        JBLabel val = new JBLabel(value);
        errorMetaGrid.add(lbl);
        errorMetaGrid.add(val);
    }

    private void addMetaRow(String label, String value) {
        JBLabel lbl = new JBLabel(label + ":");
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        JBLabel val = new JBLabel(value);
        metadataGrid.add(lbl);
        metadataGrid.add(val);
    }

    public static @NotNull String generateErrorReport(@NotNull MeshRequestEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("### GraphQL / Service Error Report\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| **Error Type** | `").append(entry.getErrorType() != null ? entry.getErrorType().getDisplayName() : "Unknown").append("` |\n");
        if (entry.getErrorCode() != null) {
            sb.append("| **Error Code** | `").append(entry.getErrorCode()).append("` |\n");
        }
        if (entry.getErrorPath() != null) {
            sb.append("| **Error Path** | `").append(entry.getErrorPath()).append("` |\n");
        }
        if (entry.getHttpStatus() != null) {
            sb.append("| **HTTP Status** | `").append(entry.getHttpStatus()).append("` |\n");
        }
        sb.append("| **Operation** | `").append(entry.getDisplayOperation()).append("` (").append(entry.getOperationType()).append(") |\n");
        if (entry.getEndpoint() != null) {
            sb.append("| **Endpoint** | `").append(entry.getEndpoint()).append("` |\n");
        }
        if (entry.getTraceId() != null) {
            sb.append("| **Trace ID** | `").append(entry.getTraceId()).append("` |\n");
        }
        if (entry.getDurationMs() != null) {
            sb.append("| **Latency** | ").append(entry.getDurationMs()).append(" ms |\n");
        }
        sb.append("\n");

        if (entry.getRootCauseMessage() != null) {
            sb.append("#### Root Cause\n```\n").append(entry.getRootCauseMessage()).append("\n```\n\n");
        }

        if (entry.getRawStackTrace() != null && !entry.getRawStackTrace().isBlank()) {
            sb.append("#### Stack Trace\n```text\n").append(entry.getRawStackTrace().trim()).append("\n```\n\n");
        }

        if (entry.getFormattedQuery() != null && !entry.getFormattedQuery().isBlank()) {
            sb.append("#### GraphQL Query\n```graphql\n").append(entry.getFormattedQuery().trim()).append("\n```\n\n");
        }

        if (entry.getFormattedVariables() != null && !entry.getFormattedVariables().isBlank()) {
            sb.append("#### Variables\n```json\n").append(entry.getFormattedVariables().trim()).append("\n```\n\n");
        }

        if (entry.getFormattedResponse() != null && !entry.getFormattedResponse().isBlank()) {
            sb.append("#### Response\n```json\n").append(entry.getFormattedResponse().trim()).append("\n```\n\n");
        }

        return sb.toString();
    }

    private static JBScrollPane createScrollPane(JComponent view) {
        JBScrollPane sp = new JBScrollPane(view);
        sp.setMinimumSize(new Dimension(0, 0));
        return sp;
    }

    private static JTextArea createMonospaceTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBorder(JBUI.Borders.empty(4));
        area.setMinimumSize(new Dimension(0, 0));
        return area;
    }

    private JPanel createMeshTopBar() {
        JPanel topBar = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        topBar.setMinimumSize(new Dimension(0, 0));
        topBar.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 8)
        ));

        // Left: Label
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        leftPanel.setMinimumSize(new Dimension(0, 0));
        JBLabel meshLabel = new JBLabel("Mesh:", AllIcons.General.Web, JBLabel.LEFT);
        meshLabel.setFont(meshLabel.getFont().deriveFont(Font.BOLD));
        meshLabel.setToolTipText(I18n.get(project, "help.mesh.address"));
        leftPanel.add(meshLabel);
        leftPanel.add(new HelpLabel(I18n.get(project, "help.mesh.address")));
        topBar.add(leftPanel, BorderLayout.WEST);

        // Center: ComboBox + Add & Remove buttons
        JPanel centerPanel = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        centerPanel.setMinimumSize(new Dimension(0, 0));
        meshUrlCombo.setEditable(true);
        meshUrlCombo.setToolTipText(I18n.get(project, "help.mesh.address"));
        meshUrlCombo.setMinimumSize(new Dimension(80, 24));

        meshUrlCombo.addActionListener(e -> {
            if (isUpdatingCombo) return;
            String url = getSelectedMeshUrl();
            if (url != null && !url.isEmpty() && project != null) {
                PluginSettingsState settings = PluginSettingsState.getInstance(project);
                if (settings != null) {
                    settings.setSelectedMeshWebAddress(url);
                }
            }
        });

        centerPanel.add(meshUrlCombo, BorderLayout.CENTER);

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0));
        btnGroup.setMinimumSize(new Dimension(0, 0));
        addMeshUrlBtn.setToolTipText(I18n.get(project, "mesh.btn.add_address.tooltip"));
        addMeshUrlBtn.setMargin(JBUI.insets(2, 4));
        addMeshUrlBtn.addActionListener(e -> onAddMeshAddress());

        removeMeshUrlBtn.setToolTipText(I18n.get(project, "mesh.btn.remove_address.tooltip"));
        removeMeshUrlBtn.setMargin(JBUI.insets(2, 4));
        removeMeshUrlBtn.addActionListener(e -> onRemoveMeshAddress());

        btnGroup.add(addMeshUrlBtn);
        btnGroup.add(removeMeshUrlBtn);
        centerPanel.add(btnGroup, BorderLayout.EAST);

        topBar.add(centerPanel, BorderLayout.CENTER);

        // Right: Open in Mesh button
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        rightPanel.setMinimumSize(new Dimension(0, 0));
        openInMeshBtn.setFont(openInMeshBtn.getFont().deriveFont(Font.BOLD));
        openInMeshBtn.setText(I18n.get(project, "mesh.btn.open_in_mesh"));
        openInMeshBtn.setToolTipText(I18n.get(project, "mesh.btn.open_in_mesh.tooltip"));
        openInMeshBtn.addActionListener(e -> openInMesh());
        rightPanel.add(openInMeshBtn);
        rightPanel.add(new HelpLabel(I18n.get(project, "help.mesh.chrome_plugin")));

        topBar.add(rightPanel, BorderLayout.EAST);

        refreshMeshUrls(null);
        return topBar;
    }

    private void onAddMeshAddress() {
        String current = getSelectedMeshUrl();
        String defaultVal = (current != null && !current.isEmpty()) ? current : "http://";
        String input = Messages.showInputDialog(
                project,
                "Enter Mesh web address / GraphQL console URL:",
                "Add Mesh Web Address",
                Messages.getQuestionIcon(),
                defaultVal,
                null
        );
        if (input != null && !input.trim().isBlank()) {
            String url = input.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            String cleaned = cleanBaseMeshUrl(url);
            PluginSettingsState.getInstance(project).addMeshWebAddress(cleaned);
            refreshMeshUrls(cleaned);
            ConfigNotifier.notifyInfo(project, "Added Mesh address: " + cleaned);
        }
    }

    private void onRemoveMeshAddress() {
        String selected = getSelectedMeshUrl();
        if (selected == null || selected.isBlank()) {
            ConfigNotifier.notifyWarning(project, "No Mesh address selected to remove.");
            return;
        }
        int answer = Messages.showYesNoDialog(
                project,
                "Remove '" + selected + "' from stored Mesh web addresses?",
                "Remove Mesh Web Address",
                Messages.getQuestionIcon()
        );
        if (answer == Messages.YES) {
            PluginSettingsState.getInstance(project).removeMeshWebAddress(selected);
            refreshMeshUrls(null);
            ConfigNotifier.notifyInfo(project, "Removed Mesh address: " + selected);
        }
    }

    private @Nullable String getSelectedMeshUrl() {
        Object item = meshUrlCombo.getEditor() != null && meshUrlCombo.getEditor().getItem() != null
                ? meshUrlCombo.getEditor().getItem()
                : meshUrlCombo.getSelectedItem();
        if (item == null) return null;
        String url = item.toString().trim();
        return url.isEmpty() ? null : url;
    }

    private void refreshMeshUrls(@Nullable String selectUrl) {
        if (project == null) return;
        isUpdatingCombo = true;
        try {
            PluginSettingsState settings = PluginSettingsState.getInstance(project);
            if (settings == null) return;
            List<String> addresses = settings.getState().meshWebAddresses;
            String currentSelected = selectUrl != null ? selectUrl : settings.getState().selectedMeshWebAddress;

            meshUrlCombo.removeAllItems();
            for (String addr : addresses) {
                meshUrlCombo.addItem(addr);
            }
            if (currentSelected != null && !currentSelected.isEmpty() && addresses.contains(currentSelected)) {
                meshUrlCombo.setSelectedItem(currentSelected);
            } else if (meshUrlCombo.getItemCount() > 0) {
                meshUrlCombo.setSelectedIndex(0);
            } else {
                meshUrlCombo.setSelectedItem(null);
                if (meshUrlCombo.getEditor() != null) {
                    meshUrlCombo.getEditor().setItem("");
                }
            }
        } finally {
            isUpdatingCombo = false;
        }
    }

    public static @NotNull String cleanBaseMeshUrl(@NotNull String url) {
        String cleaned = url.trim();
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "http://" + cleaned;
        }

        // If user pasted a full URL with query parameters (e.g. ?query=... or &query=...), strip the query string
        int qIdx = cleaned.indexOf("?query=");
        if (qIdx < 0) qIdx = cleaned.indexOf("&query=");
        if (qIdx < 0 && cleaned.contains("?variables=")) qIdx = cleaned.indexOf("?variables=");
        if (qIdx < 0 && cleaned.contains("&variables=")) qIdx = cleaned.indexOf("&variables=");
        if (qIdx >= 0) {
            cleaned = cleaned.substring(0, qIdx);
        }

        // If it ends with ? or &, strip it
        while (cleaned.endsWith("?") || cleaned.endsWith("&")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        // Ensure trailing slash for /graphiql
        if (!cleaned.contains("?") && cleaned.endsWith("/graphiql")) {
            cleaned = cleaned + "/";
        }

        return cleaned;
    }

    public static @NotNull String buildMeshBrowserUrl(@NotNull String baseMeshUrl, @Nullable MeshRequestEntry entry) {
        String cleanedBase = cleanBaseMeshUrl(baseMeshUrl);
        if (entry == null) {
            if (baseMeshUrl.contains("query=")) {
                String trimmed = baseMeshUrl.trim();
                return (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) ? "http://" + trimmed : trimmed;
            }
            return cleanedBase;
        }

        String query = entry.getFormattedQuery();
        if (query == null || query.isBlank()) {
            query = entry.getRawQuery();
        }
        if (query == null || query.isBlank()) {
            if (baseMeshUrl.contains("query=")) {
                String trimmed = baseMeshUrl.trim();
                return (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) ? "http://" + trimmed : trimmed;
            }
            return cleanedBase;
        }

        try {
            StringBuilder sb = new StringBuilder(cleanedBase);
            char sep = cleanedBase.contains("?") ? '&' : '?';
            sb.append(sep).append("query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));

            String vars = entry.getFormattedVariables();
            if (vars == null || vars.isBlank() || vars.trim().equals("{}")) {
                vars = entry.getRawVariables();
            }
            if (vars != null && !vars.isBlank() && !vars.trim().equals("{}")) {
                sb.append("&variables=").append(URLEncoder.encode(vars.trim(), StandardCharsets.UTF_8));
            }

            return sb.toString();
        } catch (Exception e) {
            return cleanedBase;
        }
    }

    public void openInMesh() {
        String url = getSelectedMeshUrl();
        if (url == null || url.isBlank()) {
            ConfigNotifier.notifyWarning(project, "Please specify or select a Mesh web address.");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        // Clean base URL for storage in settings
        String cleanedBase = cleanBaseMeshUrl(url);
        PluginSettingsState.getInstance(project).addMeshWebAddress(cleanedBase);
        refreshMeshUrls(cleanedBase);

        // Copy GraphQL query to clipboard if available
        boolean queryCopied = false;
        if (currentEntry != null) {
            String query = currentEntry.getFormattedQuery();
            if (query == null || query.isBlank()) {
                query = currentEntry.getRawQuery();
            }
            if (query != null && !query.isBlank()) {
                CopyPasteManager.getInstance().setContents(new StringSelection(query));
                queryCopied = true;
            }
        }

        String targetBrowserUrl = buildMeshBrowserUrl(url, currentEntry);

        try {
            BrowserUtil.browse(targetBrowserUrl);
            if (queryCopied) {
                ConfigNotifier.notifyInfo(project, "Opened Mesh with query in browser: " + cleanedBase + " (also copied to clipboard)");
            } else {
                ConfigNotifier.notifyInfo(project, "Opened Mesh in browser: " + cleanedBase);
            }
        } catch (Exception ex) {
            ConfigNotifier.notifyError(project, "Failed to open browser: " + ex.getMessage());
        }
    }

    boolean isMeshTopBarVisible() {
        return topBar != null && topBar.isVisible();
    }
}
