package configswitcher.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.i18n.PluginLanguage;
import configswitcher.model.ExecutionMode;
import configswitcher.model.GitPatchEntry;
import configswitcher.model.SwitchMode;
import configswitcher.model.TerminalShellType;
import configswitcher.service.AppRunManager;
import configswitcher.service.ConfigScannerService;
import configswitcher.service.GitPatchService;
import configswitcher.service.SessionLogManager;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

@SuppressWarnings("deprecation")
public class PluginSettingsConfigurable implements SearchableConfigurable {
    public static final String ID = "configswitcher.settings";

    private final Project project;
    private final PluginSettingsState settings;

    private JPanel rootPanel;

    // Language
    private JPanel languagePanel;
    private JBLabel languageLabel;
    private ComboBox<PluginLanguage> languageCombo;

    // Git Patches
    private JPanel patchesPanel;
    private DefaultTableModel patchTableModel;
    private JBTable patchTable;
    private JButton pastePatchBtn;
    private JButton addPatchFileBtn;
    private JButton removePatchBtn;
    private JButton revertNowBtn;
    private JBCheckBox revertBeforeApplyCheckbox;
    private JBCheckBox autoRevertPatchesCheckbox;
    private JBLabel patchStorageDirLabel;
    private JBTextField patchStorageDirField;

    // Pre-Run Stage (Build command)
    private JPanel preRunPanel;
    private JBCheckBox enablePreRunCheckbox;
    private JBLabel preRunCmdLabel;
    private JBTextField preRunCommandField;
    private JBCheckBox skipPreRunCheckbox;

    // Run Stage (Application command)
    private JPanel runStagePanel;
    private JBLabel runTemplateLabel;
    private JButton recalculateBtn;
    private JBTextArea runCommandTemplateArea;
    private JBLabel logFilterLabel;
    private JBCheckBox terminalOutputDebugCheckbox;
    private JBCheckBox terminalOutputInfoCheckbox;
    private JBCheckBox terminalOutputWarnCheckbox;
    private JBCheckBox terminalOutputErrorCheckbox;
    private JBCheckBox colorizeErrorOutputCheckbox;
    private JBCheckBox terminalShowErrorStackTraceCheckbox;
    private JLabel reloadStatusLabel;

    // GraphQL Mesh Observability
    private JPanel meshPanel;
    private JBCheckBox enableMeshCaptureCheckbox;
    private JBLabel meshEndpointLabel;
    private JBTextField meshEndpointUrlField;
    private JBLabel meshMaxHistoryLabel;
    private JBTextField meshMaxHistoryField;

    // Logs & Diagnostics
    private JPanel logsPanel;
    private JBLabel logPathLabel;
    private JLabel logDescLabel;
    private JButton openLogFolderBtn;

    // Help Labels ('?' blue icons)
    private HelpLabel languageHelp;
    private HelpLabel patchesTableHelp;
    private HelpLabel revertBeforeApplyHelp;
    private HelpLabel autoRevertPatchesHelp;
    private HelpLabel patchStorageDirHelp;
    private HelpLabel enablePreRunHelp;
    private HelpLabel preRunCmdHelp;
    private HelpLabel skipPreRunHelp;
    private HelpLabel runTemplateHelp;
    private HelpLabel recalculateHelp;
    private HelpLabel logFilterHelp;
    private HelpLabel colorizeErrorHelp;
    private HelpLabel terminalShowErrorStackTraceHelp;
    private HelpLabel enableMeshCaptureHelp;
    private HelpLabel meshEndpointHelp;
    private HelpLabel meshMaxHistoryHelp;
    private HelpLabel logsHelp;

    public PluginSettingsConfigurable(@NotNull Project project) {
        this.project = project;
        this.settings = PluginSettingsState.getInstance(project);
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Spring Boot Profile Switcher";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rootPanel = new JPanel();
        rootPanel.setLayout(new BoxLayout(rootPanel, BoxLayout.Y_AXIS));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // =========================================================================
        // 0. Language Selector Panel
        // =========================================================================
        languagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)));
        languagePanel.setBorder(BorderFactory.createTitledBorder(I18n.get(PluginLanguage.EN, "settings.language.title")));
        languageLabel = new JBLabel(I18n.get(PluginLanguage.EN, "settings.language.label"));
        languageCombo = new ComboBox<>(PluginLanguage.values());
        languageHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.language"));
        languageCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PluginLanguage pl) {
                    setText(pl.getDisplayName());
                }
                return this;
            }
        });
        languageCombo.addActionListener(e -> {
            PluginLanguage selected = (PluginLanguage) languageCombo.getSelectedItem();
            if (selected != null) {
                updateLocalization(selected);
            }
        });
        languagePanel.add(languageLabel);
        languagePanel.add(languageCombo);
        languagePanel.add(languageHelp);
        rootPanel.add(languagePanel);
        rootPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // =========================================================================
        // 1. Pre-Run Stage 1: Git Patches Panel
        // =========================================================================
        patchesPanel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        patchesPanel.setBorder(BorderFactory.createTitledBorder(I18n.get(PluginLanguage.EN, "settings.patches.title")));

        String[] columns = {"Active", "Patch File Path", "Description"};
        patchTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };
        patchTable = new JBTable(patchTableModel);
        patchTable.setPreferredScrollableViewportSize(new Dimension(JBUI.scale(550), JBUI.scale(100)));
        patchTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(50));

        patchesPanel.add(new JScrollPane(patchTable), BorderLayout.CENTER);

        JPanel patchButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        pastePatchBtn = new JButton("Paste from Clipboard...");
        pastePatchBtn.setToolTipText("Paste git diff from clipboard, save as file in project (.idea/patches/), and add to list");
        pastePatchBtn.addActionListener(e -> {
            String clip = GitPatchService.getClipboardText();
            PluginLanguage lang = (PluginLanguage) languageCombo.getSelectedItem();
            if (lang == null) lang = PluginLanguage.EN;
            if (clip == null || clip.isBlank()) {
                Messages.showWarningDialog(project,
                        I18n.get(lang, "dialog.patches.empty_clipboard.msg"),
                        I18n.get(lang, "dialog.patches.empty_clipboard.title"));
                return;
            }
            PluginSettingsState.State st = settings.getState();
            PasteGitPatchDialog dlg = new PasteGitPatchDialog(project, clip, st.selectedProfile);
            if (dlg.showAndGet()) {
                GitPatchEntry created = dlg.getCreatedEntry();
                if (created != null) {
                    patchTableModel.addRow(new Object[]{
                            created.isEnabled(),
                            created.getPatchPath(),
                            created.getDescription()
                    });
                }
            }
        });
        patchButtonsPanel.add(pastePatchBtn);

        addPatchFileBtn = new JButton("Add File...");
        addPatchFileBtn.addActionListener(e -> {
            PluginLanguage lang = (PluginLanguage) languageCombo.getSelectedItem();
            if (lang == null) lang = PluginLanguage.EN;
            FileChooserDescriptor desc = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withFileFilter(vf -> vf.getName().endsWith(".patch") || vf.getName().endsWith(".diff"))
                    .withTitle(I18n.get(lang, "dialog.patches.select_file.title"));
            VirtualFile vf = FileChooser.chooseFile(desc, project, null);
            if (vf != null) {
                String relPath = vf.getPath();
                String basePath = project.getBasePath();
                if (basePath != null && relPath.startsWith(basePath)) {
                    relPath = relPath.substring(basePath.length());
                    if (relPath.startsWith("/") || relPath.startsWith("\\")) relPath = relPath.substring(1);
                }
                patchTableModel.addRow(new Object[]{true, relPath, ""});
            }
        });
        patchButtonsPanel.add(addPatchFileBtn);

        removePatchBtn = new JButton("Remove Selected");
        removePatchBtn.addActionListener(e -> {
            int row = patchTable.getSelectedRow();
            if (row >= 0) {
                patchTableModel.removeRow(row);
            }
        });
        patchButtonsPanel.add(removePatchBtn);

        revertNowBtn = new JButton("Revert Applied Patches Now");
        revertNowBtn.setToolTipText("Manually reverse any git patches that are currently applied to the working copy");
        revertNowBtn.addActionListener(e -> {
            PluginLanguage lang = (PluginLanguage) languageCombo.getSelectedItem();
            if (lang == null) lang = PluginLanguage.EN;
            int count = GitPatchService.getInstance(project).revertAppliedPatches();
            Messages.showInfoMessage(project,
                    I18n.get(lang, "dialog.patches.reverted.msg", count),
                    I18n.get(lang, "dialog.patches.reverted.title"));
        });
        patchButtonsPanel.add(revertNowBtn);

        patchesTableHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.patches.table"));
        patchButtonsPanel.add(patchesTableHelp);

        JPanel patchOptionsPanel = new JPanel(new GridLayout(3, 1, 0, JBUI.scale(2)));

        JPanel revertRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        revertBeforeApplyCheckbox = new JBCheckBox("Automatically revert changes in patch files before applying (Pre-Run Stage 1)");
        revertBeforeApplyHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.patches.revert_before_apply"));
        revertRow.add(revertBeforeApplyCheckbox);
        revertRow.add(revertBeforeApplyHelp);
        patchOptionsPanel.add(revertRow);

        JPanel autoRevertRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        autoRevertPatchesCheckbox = new JBCheckBox("Automatically revert applied patches after build stage finishes (and on exit)");
        autoRevertPatchesHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.patches.auto_revert"));
        autoRevertRow.add(autoRevertPatchesCheckbox);
        autoRevertRow.add(autoRevertPatchesHelp);
        patchOptionsPanel.add(autoRevertRow);

        JPanel storageDirRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JPanel storageDirWest = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        patchStorageDirLabel = new JBLabel("Patch storage folder in project:");
        patchStorageDirHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.patches.storage_folder"));
        storageDirWest.add(patchStorageDirLabel);
        storageDirWest.add(patchStorageDirHelp);
        storageDirRow.add(storageDirWest, BorderLayout.WEST);
        patchStorageDirField = new JBTextField(".idea/patches");
        storageDirRow.add(patchStorageDirField, BorderLayout.CENTER);
        patchOptionsPanel.add(storageDirRow);

        JPanel patchBottomPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        patchBottomPanel.add(patchButtonsPanel, BorderLayout.NORTH);
        patchBottomPanel.add(patchOptionsPanel, BorderLayout.SOUTH);

        patchesPanel.add(patchBottomPanel, BorderLayout.SOUTH);

        rootPanel.add(patchesPanel);
        rootPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // =========================================================================
        // 2. Pre-Run Stage 2: Build Command Panel
        // =========================================================================
        preRunPanel = new JPanel(new GridBagLayout());
        preRunPanel.setBorder(BorderFactory.createTitledBorder(I18n.get(PluginLanguage.EN, "settings.build.title")));
        gbc.gridy = 0;

        JPanel enablePreRunRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        enablePreRunCheckbox = new JBCheckBox("Execute Pre-Run build command before launching application");
        enablePreRunHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.build.enable"));
        enablePreRunRow.add(enablePreRunCheckbox);
        enablePreRunRow.add(enablePreRunHelp);
        preRunPanel.add(enablePreRunRow, gbc);

        gbc.gridy++;
        JPanel preRunCmdRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JPanel preRunCmdWest = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        preRunCmdLabel = new JBLabel("Pre-Run Command:");
        preRunCmdHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.build.command"));
        preRunCmdWest.add(preRunCmdLabel);
        preRunCmdWest.add(preRunCmdHelp);
        preRunCmdRow.add(preRunCmdWest, BorderLayout.WEST);
        preRunCommandField = new JBTextField();
        preRunCmdRow.add(preRunCommandField, BorderLayout.CENTER);
        preRunPanel.add(preRunCmdRow, gbc);

        gbc.gridy++;
        JPanel skipPreRunRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        skipPreRunCheckbox = new JBCheckBox("Skip Pre-Run build if application is already running");
        skipPreRunHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.build.skip_if_running"));
        skipPreRunRow.add(skipPreRunCheckbox);
        skipPreRunRow.add(skipPreRunHelp);
        preRunPanel.add(skipPreRunRow, gbc);

        rootPanel.add(preRunPanel);
        rootPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // =========================================================================
        // 3. Run Stage: Application Command Panel
        // =========================================================================
        runStagePanel = new JPanel(new GridBagLayout());
        runStagePanel.setBorder(BorderFactory.createTitledBorder(I18n.get(PluginLanguage.EN, "settings.run.title")));
        gbc.gridy = 0;

        JPanel runTemplatePanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel templateHeader = new JPanel(new BorderLayout(JBUI.scale(8), 0));

        JPanel templateHeaderWest = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        runTemplateLabel = new JBLabel("Command Template (placeholders: {profile}, {filePath}):");
        runTemplateHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.run.template"));
        templateHeaderWest.add(runTemplateLabel);
        templateHeaderWest.add(runTemplateHelp);
        templateHeader.add(templateHeaderWest, BorderLayout.WEST);

        JPanel recalcEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        recalculateBtn = new JButton("Recalculate Command Template", AllIcons.Actions.Refresh);
        recalculateBtn.setToolTipText("Auto-detect target JAR and N2O config path from current project");
        recalculateBtn.addActionListener(e -> {
            String calculated = AppRunManager.calculateDefaultCommandTemplate(project);
            runCommandTemplateArea.setText(calculated);
        });
        recalculateHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.run.recalc"));
        recalcEast.add(recalculateBtn);
        recalcEast.add(recalculateHelp);
        templateHeader.add(recalcEast, BorderLayout.EAST);

        runTemplatePanel.add(templateHeader, BorderLayout.NORTH);
        runCommandTemplateArea = new JBTextArea(4, 50);
        runCommandTemplateArea.setLineWrap(true);
        runCommandTemplateArea.setWrapStyleWord(true);
        JBScrollPane scrollPane = new JBScrollPane(
                runCommandTemplateArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        scrollPane.setPreferredSize(new Dimension(JBUI.scale(550), JBUI.scale(85)));
        runTemplatePanel.add(scrollPane, BorderLayout.CENTER);
        runStagePanel.add(runTemplatePanel, gbc);

        gbc.gridy++;
        JPanel logFilterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        logFilterLabel = new JBLabel("Terminal Output Log Levels:");
        logFilterHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.run.log_levels"));
        terminalOutputDebugCheckbox = new JBCheckBox("DEBUG");
        terminalOutputInfoCheckbox = new JBCheckBox("INFO");
        terminalOutputWarnCheckbox = new JBCheckBox("WARN");
        terminalOutputErrorCheckbox = new JBCheckBox("ERROR");
        terminalOutputDebugCheckbox.setToolTipText("Output DEBUG and TRACE logs to terminal console");
        terminalOutputInfoCheckbox.setToolTipText("Output INFO logs to terminal console");
        terminalOutputWarnCheckbox.setToolTipText("Output WARN logs to terminal console");
        terminalOutputErrorCheckbox.setToolTipText("Output ERROR logs to terminal console");

        colorizeErrorOutputCheckbox = new JBCheckBox("Paint errors in red");
        colorizeErrorHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.run.colorize_error"));

        terminalShowErrorStackTraceCheckbox = new JBCheckBox("Call stack");
        terminalShowErrorStackTraceHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.run.call_stack"));

        logFilterRow.add(logFilterLabel);
        logFilterRow.add(logFilterHelp);
        logFilterRow.add(terminalOutputDebugCheckbox);
        logFilterRow.add(terminalOutputInfoCheckbox);
        logFilterRow.add(terminalOutputWarnCheckbox);
        logFilterRow.add(terminalOutputErrorCheckbox);
        logFilterRow.add(colorizeErrorOutputCheckbox);
        logFilterRow.add(colorizeErrorHelp);
        logFilterRow.add(terminalShowErrorStackTraceCheckbox);
        logFilterRow.add(terminalShowErrorStackTraceHelp);
        runStagePanel.add(logFilterRow, gbc);

        rootPanel.add(runStagePanel);
        rootPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // =========================================================================
        // 4. GraphQL Mesh Observability Section
        // =========================================================================
        meshPanel = new JPanel(new GridBagLayout());
        meshPanel.setBorder(BorderFactory.createTitledBorder(I18n.get(PluginLanguage.EN, "settings.mesh.title")));
        GridBagConstraints meshGbc = new GridBagConstraints();
        meshGbc.anchor = GridBagConstraints.WEST;
        meshGbc.fill = GridBagConstraints.HORIZONTAL;
        meshGbc.weightx = 1.0;
        meshGbc.insets = JBUI.insets(2, 4);
        meshGbc.gridx = 0;
        meshGbc.gridy = 0;

        JPanel meshCheckRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        enableMeshCaptureCheckbox = new JBCheckBox("Enable GraphQL Mesh request & response analyzer", true);
        enableMeshCaptureHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.mesh.enable"));
        meshCheckRow.add(enableMeshCaptureCheckbox);
        meshCheckRow.add(enableMeshCaptureHelp);
        meshPanel.add(meshCheckRow, meshGbc);

        meshGbc.gridy++;
        JPanel meshUrlRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JPanel meshUrlWest = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        meshEndpointLabel = new JBLabel("Default GraphQL Endpoint:");
        meshEndpointHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.mesh.endpoint"));
        meshUrlWest.add(meshEndpointLabel);
        meshUrlWest.add(meshEndpointHelp);
        meshUrlRow.add(meshUrlWest, BorderLayout.WEST);
        meshEndpointUrlField = new JBTextField();
        meshUrlRow.add(meshEndpointUrlField, BorderLayout.CENTER);
        meshPanel.add(meshUrlRow, meshGbc);

        meshGbc.gridy++;
        JPanel meshHistRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JPanel meshHistWest = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        meshMaxHistoryLabel = new JBLabel("Max Captured History Entries:");
        meshMaxHistoryHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.mesh.max_history"));
        meshHistWest.add(meshMaxHistoryLabel);
        meshHistWest.add(meshMaxHistoryHelp);
        meshHistRow.add(meshHistWest, BorderLayout.WEST);
        meshMaxHistoryField = new JBTextField("500");
        meshHistRow.add(meshMaxHistoryField, BorderLayout.CENTER);
        meshPanel.add(meshHistRow, meshGbc);

        rootPanel.add(meshPanel);
        rootPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // =========================================================================
        // 9. Diagnostics & Logs Section
        // =========================================================================
        logsPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        logsPanel.setBorder(BorderFactory.createTitledBorder(I18n.get(PluginLanguage.EN, "settings.logs.title")));

        JPanel logInfoPanel = new JPanel(new GridLayout(2, 1, 0, JBUI.scale(2)));
        JPanel logFolderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        logPathLabel = new JBLabel("Log folder: " + getProjectLogDirectoryPath());
        logPathLabel.setCopyable(true);
        logsHelp = new HelpLabel(I18n.get(PluginLanguage.EN, "help.logs.diagnostics"));
        logFolderRow.add(logPathLabel);
        logFolderRow.add(logsHelp);
        logInfoPanel.add(logFolderRow);

        logDescLabel = new JLabel("Stores session.log (application run output), mesh-session.json, and diagnostic logs for pipeline execution.");
        logDescLabel.setFont(logDescLabel.getFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(11)));
        logDescLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        logInfoPanel.add(logDescLabel);

        logsPanel.add(logInfoPanel, BorderLayout.CENTER);

        JPanel logActionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        openLogFolderBtn = new JButton("Open Log Folder");
        openLogFolderBtn.addActionListener(e -> configswitcher.service.SessionLogManager.getInstance(project).openLogFolder());
        logActionsPanel.add(openLogFolderBtn);

        logsPanel.add(logActionsPanel, BorderLayout.EAST);

        reloadStatusLabel = new JLabel("");
        reloadStatusLabel.setFont(reloadStatusLabel.getFont().deriveFont(Font.PLAIN, JBUI.scaleFontSize(11)));

        JPanel logsWrapper = new JPanel(new BorderLayout());
        logsWrapper.add(logsPanel, BorderLayout.CENTER);
        logsWrapper.add(reloadStatusLabel, BorderLayout.SOUTH);

        rootPanel.add(logsWrapper);

        reset();
        return rootPanel;
    }

    private void updatePanelBorder(JPanel panel, String title) {
        if (panel != null && panel.getBorder() instanceof TitledBorder tb) {
            tb.setTitle(title);
            panel.repaint();
        }
    }

    private void updateLocalization(PluginLanguage lang) {
        if (lang == null) lang = PluginLanguage.EN;

        updatePanelBorder(languagePanel, I18n.get(lang, "settings.language.title"));
        if (languageLabel != null) languageLabel.setText(I18n.get(lang, "settings.language.label"));
        if (languageHelp != null) languageHelp.updateTooltip(I18n.get(lang, "help.language"));

        updatePanelBorder(patchesPanel, I18n.get(lang, "settings.patches.title"));
        if (patchTable != null && patchTable.getColumnModel().getColumnCount() >= 3) {
            patchTable.getColumnModel().getColumn(0).setHeaderValue(I18n.get(lang, "settings.patches.col.active"));
            patchTable.getColumnModel().getColumn(1).setHeaderValue(I18n.get(lang, "settings.patches.col.path"));
            patchTable.getColumnModel().getColumn(2).setHeaderValue(I18n.get(lang, "settings.patches.col.description"));
            patchTable.getTableHeader().repaint();
        }
        if (pastePatchBtn != null) {
            pastePatchBtn.setText(I18n.get(lang, "settings.patches.btn.paste"));
            pastePatchBtn.setToolTipText(I18n.get(lang, "settings.patches.btn.paste.tooltip"));
        }
        if (addPatchFileBtn != null) addPatchFileBtn.setText(I18n.get(lang, "settings.patches.btn.add"));
        if (removePatchBtn != null) removePatchBtn.setText(I18n.get(lang, "settings.patches.btn.remove"));
        if (revertNowBtn != null) {
            revertNowBtn.setText(I18n.get(lang, "settings.patches.btn.revert"));
            revertNowBtn.setToolTipText(I18n.get(lang, "settings.patches.btn.revert.tooltip"));
        }
        if (patchesTableHelp != null) patchesTableHelp.updateTooltip(I18n.get(lang, "help.patches.table"));
        if (revertBeforeApplyCheckbox != null) {
            revertBeforeApplyCheckbox.setText(I18n.get(lang, "settings.patches.revert_before_apply"));
            revertBeforeApplyCheckbox.setToolTipText(I18n.get(lang, "settings.patches.revert_before_apply.tooltip"));
        }
        if (revertBeforeApplyHelp != null) revertBeforeApplyHelp.updateTooltip(I18n.get(lang, "help.patches.revert_before_apply"));
        if (autoRevertPatchesCheckbox != null) {
            autoRevertPatchesCheckbox.setText(I18n.get(lang, "settings.patches.auto_revert"));
            autoRevertPatchesCheckbox.setToolTipText(I18n.get(lang, "settings.patches.auto_revert.tooltip"));
        }
        if (autoRevertPatchesHelp != null) autoRevertPatchesHelp.updateTooltip(I18n.get(lang, "help.patches.auto_revert"));
        if (patchStorageDirLabel != null) patchStorageDirLabel.setText(I18n.get(lang, "settings.patches.storage_folder"));
        if (patchStorageDirHelp != null) patchStorageDirHelp.updateTooltip(I18n.get(lang, "help.patches.storage_folder"));

        updatePanelBorder(preRunPanel, I18n.get(lang, "settings.build.title"));
        if (enablePreRunCheckbox != null) enablePreRunCheckbox.setText(I18n.get(lang, "settings.build.enable"));
        if (enablePreRunHelp != null) enablePreRunHelp.updateTooltip(I18n.get(lang, "help.build.enable"));
        if (preRunCmdLabel != null) preRunCmdLabel.setText(I18n.get(lang, "settings.build.command_label"));
        if (preRunCmdHelp != null) preRunCmdHelp.updateTooltip(I18n.get(lang, "help.build.command"));
        if (skipPreRunCheckbox != null) skipPreRunCheckbox.setText(I18n.get(lang, "settings.build.skip_if_running"));
        if (skipPreRunHelp != null) skipPreRunHelp.updateTooltip(I18n.get(lang, "help.build.skip_if_running"));

        updatePanelBorder(runStagePanel, I18n.get(lang, "settings.run.title"));
        if (runTemplateLabel != null) runTemplateLabel.setText(I18n.get(lang, "settings.run.template_label"));
        if (runTemplateHelp != null) runTemplateHelp.updateTooltip(I18n.get(lang, "help.run.template"));
        if (recalculateBtn != null) {
            recalculateBtn.setText(I18n.get(lang, "settings.run.recalc_btn"));
            recalculateBtn.setToolTipText(I18n.get(lang, "settings.run.recalc_tooltip"));
        }
        if (recalculateHelp != null) recalculateHelp.updateTooltip(I18n.get(lang, "help.run.recalc"));
        if (logFilterLabel != null) logFilterLabel.setText(I18n.get(lang, "settings.run.log_levels_label"));
        if (logFilterHelp != null) logFilterHelp.updateTooltip(I18n.get(lang, "help.run.log_levels"));
        if (terminalOutputDebugCheckbox != null) terminalOutputDebugCheckbox.setToolTipText(I18n.get(lang, "settings.run.log_debug_tooltip"));
        if (terminalOutputInfoCheckbox != null) terminalOutputInfoCheckbox.setToolTipText(I18n.get(lang, "settings.run.log_info_tooltip"));
        if (terminalOutputWarnCheckbox != null) terminalOutputWarnCheckbox.setToolTipText(I18n.get(lang, "settings.run.log_warn_tooltip"));
        if (terminalOutputErrorCheckbox != null) terminalOutputErrorCheckbox.setToolTipText(I18n.get(lang, "settings.run.log_error_tooltip"));
        if (colorizeErrorOutputCheckbox != null) {
            colorizeErrorOutputCheckbox.setText(I18n.get(lang, "settings.run.colorize_error"));
            colorizeErrorOutputCheckbox.setToolTipText(I18n.get(lang, "settings.run.colorize_error_tooltip"));
        }
        if (colorizeErrorHelp != null) colorizeErrorHelp.updateTooltip(I18n.get(lang, "help.run.colorize_error"));
        if (terminalShowErrorStackTraceCheckbox != null) {
            terminalShowErrorStackTraceCheckbox.setText(I18n.get(lang, "settings.run.call_stack"));
            terminalShowErrorStackTraceCheckbox.setToolTipText(I18n.get(lang, "settings.run.call_stack_tooltip"));
        }
        if (terminalShowErrorStackTraceHelp != null) terminalShowErrorStackTraceHelp.updateTooltip(I18n.get(lang, "help.run.call_stack"));

        updatePanelBorder(meshPanel, I18n.get(lang, "settings.mesh.title"));
        if (enableMeshCaptureCheckbox != null) enableMeshCaptureCheckbox.setText(I18n.get(lang, "settings.mesh.enable"));
        if (enableMeshCaptureHelp != null) enableMeshCaptureHelp.updateTooltip(I18n.get(lang, "help.mesh.enable"));
        if (meshEndpointLabel != null) meshEndpointLabel.setText(I18n.get(lang, "settings.mesh.endpoint_label"));
        if (meshEndpointHelp != null) meshEndpointHelp.updateTooltip(I18n.get(lang, "help.mesh.endpoint"));
        if (meshMaxHistoryLabel != null) meshMaxHistoryLabel.setText(I18n.get(lang, "settings.mesh.max_history_label"));
        if (meshMaxHistoryHelp != null) meshMaxHistoryHelp.updateTooltip(I18n.get(lang, "help.mesh.max_history"));

        updatePanelBorder(logsPanel, I18n.get(lang, "settings.logs.title"));
        if (logPathLabel != null) logPathLabel.setText(I18n.get(lang, "settings.logs.folder_prefix") + " " + getProjectLogDirectoryPath());
        if (logsHelp != null) logsHelp.updateTooltip(I18n.get(lang, "help.logs.diagnostics"));
        if (logDescLabel != null) logDescLabel.setText(I18n.get(lang, "settings.logs.desc"));
        if (openLogFolderBtn != null) openLogFolderBtn.setText(I18n.get(lang, "settings.logs.open_btn"));
    }

    private @NotNull String getProjectLogDirectoryPath() {
        try {
            SessionLogManager sessionLogManager = SessionLogManager.getInstance(project);
            if (sessionLogManager != null) {
                return sessionLogManager.getLogDirectory().getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        return ConfigSwitcherLog.getLogDirectory(project).getAbsolutePath();
    }

    private List<GitPatchEntry> getPatchesFromTable() {
        List<GitPatchEntry> list = new ArrayList<>();
        if (patchTableModel == null) return list;
        for (int i = 0; i < patchTableModel.getRowCount(); i++) {
            Boolean enabled = (Boolean) patchTableModel.getValueAt(i, 0);
            String path = (String) patchTableModel.getValueAt(i, 1);
            String desc = (String) patchTableModel.getValueAt(i, 2);
            if (path != null && !path.trim().isEmpty()) {
                list.add(new GitPatchEntry(
                        Boolean.TRUE.equals(enabled),
                        path.trim(),
                        desc != null ? desc.trim() : "",
                        ""
                ));
            }
        }
        return list;
    }

    private void setPatchesToTable(List<GitPatchEntry> patches) {
        if (patchTableModel == null) return;
        patchTableModel.setRowCount(0);
        for (GitPatchEntry entry : patches) {
            patchTableModel.addRow(new Object[]{
                    entry.isEnabled(),
                    entry.getPatchPath(),
                    entry.getDescription()
            });
        }
    }

    @Override
    public boolean isModified() {
        PluginSettingsState.State state = settings.getState();
        PluginLanguage curLang = languageCombo != null ? (PluginLanguage) languageCombo.getSelectedItem() : state.language;
        return (curLang != null && curLang != state.language) ||
                revertBeforeApplyCheckbox.isSelected() != state.revertPatchFilesBeforeApply ||
                autoRevertPatchesCheckbox.isSelected() != state.autoRevertPatches ||
                !patchStorageDirField.getText().trim().equals(state.patchStorageDir) ||
                !getPatchesFromTable().equals(state.gitPatches) ||
                enablePreRunCheckbox.isSelected() != state.enablePreRun ||
                !preRunCommandField.getText().trim().equals(state.preRunCommand) ||
                skipPreRunCheckbox.isSelected() != state.skipPreRunIfRunning ||
                !runCommandTemplateArea.getText().trim().equals(state.runCommandTemplate) ||
                terminalOutputDebugCheckbox.isSelected() != state.terminalOutputDebug ||
                terminalOutputInfoCheckbox.isSelected() != state.terminalOutputInfo ||
                terminalOutputWarnCheckbox.isSelected() != state.terminalOutputWarn ||
                terminalOutputErrorCheckbox.isSelected() != state.terminalOutputError ||
                colorizeErrorOutputCheckbox.isSelected() != state.colorizeErrorOutputInTerminal ||
                terminalShowErrorStackTraceCheckbox.isSelected() != state.terminalShowErrorStackTrace ||
                enableMeshCaptureCheckbox.isSelected() != state.enableMeshCapture ||
                !meshEndpointUrlField.getText().trim().equals(state.selectedMeshWebAddress != null && !state.selectedMeshWebAddress.isEmpty() ? state.selectedMeshWebAddress : state.meshDefaultEndpointUrl) ||
                !meshMaxHistoryField.getText().trim().equals(String.valueOf(state.meshMaxHistory));
    }

    @Override
    public void apply() {
        PluginSettingsState.State state = settings.getState();
        if (languageCombo != null && languageCombo.getSelectedItem() instanceof PluginLanguage pl) {
            state.language = pl;
        }
        state.executionMode = ExecutionMode.COMMAND_PIPELINE;
        state.revertPatchFilesBeforeApply = revertBeforeApplyCheckbox.isSelected();
        state.autoRevertPatches = autoRevertPatchesCheckbox.isSelected();
        state.patchStorageDir = patchStorageDirField.getText().trim();

        state.gitPatches.clear();
        state.gitPatches.addAll(getPatchesFromTable());

        state.enablePreRun = enablePreRunCheckbox.isSelected();
        state.preRunCommand = preRunCommandField.getText().trim();
        state.preRunWorkingDir = "";
        state.skipPreRunIfRunning = skipPreRunCheckbox.isSelected();
        state.skipPreRunOnQuickSwitch = skipPreRunCheckbox.isSelected();

        state.runCommandTemplate = runCommandTemplateArea.getText().trim();
        state.runWorkingDir = "";
        state.executeInTerminal = true;
        state.terminalShellType = TerminalShellType.AUTO;
        state.terminalOutputDebug = terminalOutputDebugCheckbox.isSelected();
        state.terminalOutputInfo = terminalOutputInfoCheckbox.isSelected();
        state.terminalOutputWarn = terminalOutputWarnCheckbox.isSelected();
        state.terminalOutputError = terminalOutputErrorCheckbox.isSelected();
        state.colorizeErrorOutputInTerminal = colorizeErrorOutputCheckbox.isSelected();
        state.terminalShowErrorStackTrace = terminalShowErrorStackTraceCheckbox.isSelected();

        state.switchMode = SwitchMode.PROFILE_ARGUMENTS;
        state.autoRestart = true;
        state.backupFileBeforeSwap = true;

        state.enableMeshCapture = enableMeshCaptureCheckbox.isSelected();
        String endpoint = meshEndpointUrlField.getText().trim();
        state.meshDefaultEndpointUrl = endpoint;
        if (!endpoint.isEmpty()) {
            if (!state.meshWebAddresses.contains(endpoint)) {
                state.meshWebAddresses.add(endpoint);
            }
            state.selectedMeshWebAddress = endpoint;
        }
        try {
            state.meshMaxHistory = Integer.parseInt(meshMaxHistoryField.getText().trim());
        } catch (Exception ignored) {}

        ConfigScannerService.getInstance(project).refreshConfigurations();
        configswitcher.mesh.ui.MeshToolWindowFactory.reinitToolWindow(project);
    }

    @Override
    public void reset() {
        PluginSettingsState.State state = settings.getState();
        PluginLanguage lang = state.language != null ? state.language : PluginLanguage.EN;
        if (languageCombo != null) {
            languageCombo.setSelectedItem(lang);
        }
        updateLocalization(lang);

        if (revertBeforeApplyCheckbox != null) revertBeforeApplyCheckbox.setSelected(state.revertPatchFilesBeforeApply);
        if (autoRevertPatchesCheckbox != null) autoRevertPatchesCheckbox.setSelected(state.autoRevertPatches);
        if (patchStorageDirField != null) patchStorageDirField.setText(state.patchStorageDir);
        setPatchesToTable(state.gitPatches);

        if (enablePreRunCheckbox != null) enablePreRunCheckbox.setSelected(state.enablePreRun);
        if (preRunCommandField != null) preRunCommandField.setText(state.preRunCommand);
        if (skipPreRunCheckbox != null) skipPreRunCheckbox.setSelected(state.skipPreRunIfRunning);

        if (PluginSettingsState.isLegacyDefaultTemplate(state.runCommandTemplate)) {
            state.runCommandTemplate = AppRunManager.calculateDefaultCommandTemplate(project);
        }
        if (runCommandTemplateArea != null) runCommandTemplateArea.setText(state.runCommandTemplate);
        if (terminalOutputDebugCheckbox != null) terminalOutputDebugCheckbox.setSelected(state.terminalOutputDebug);
        if (terminalOutputInfoCheckbox != null) terminalOutputInfoCheckbox.setSelected(state.terminalOutputInfo);
        if (terminalOutputWarnCheckbox != null) terminalOutputWarnCheckbox.setSelected(state.terminalOutputWarn);
        if (terminalOutputErrorCheckbox != null) terminalOutputErrorCheckbox.setSelected(state.terminalOutputError);
        if (colorizeErrorOutputCheckbox != null) colorizeErrorOutputCheckbox.setSelected(state.colorizeErrorOutputInTerminal);
        if (terminalShowErrorStackTraceCheckbox != null) terminalShowErrorStackTraceCheckbox.setSelected(state.terminalShowErrorStackTrace);

        if (enableMeshCaptureCheckbox != null) enableMeshCaptureCheckbox.setSelected(state.enableMeshCapture);
        if (meshEndpointUrlField != null) meshEndpointUrlField.setText(state.selectedMeshWebAddress != null && !state.selectedMeshWebAddress.isEmpty() ? state.selectedMeshWebAddress : state.meshDefaultEndpointUrl);
        if (meshMaxHistoryField != null) meshMaxHistoryField.setText(String.valueOf(state.meshMaxHistory));
    }
}
