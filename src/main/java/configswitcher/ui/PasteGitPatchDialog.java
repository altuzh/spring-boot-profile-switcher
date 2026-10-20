package configswitcher.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.model.GitPatchEntry;
import configswitcher.service.GitPatchService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class PasteGitPatchDialog extends DialogWrapper {

    private final Project project;
    private final String clipboardText;
    private final String currentProfile;

    private JBTextField patchNameField;
    private JBTextField descriptionField;
    private JBTextArea previewArea;

    private GitPatchEntry createdEntry = null;

    public PasteGitPatchDialog(@NotNull Project project, @NotNull String clipboardText, @Nullable String defaultProfile) {
        super(project, true);
        this.project = project;
        this.clipboardText = clipboardText;
        this.currentProfile = (defaultProfile != null && !defaultProfile.equalsIgnoreCase("default")) ? defaultProfile : "";

        setTitle(I18n.get(project, "dialog.paste_patch.title"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(550), JBUI.scale(320)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;

        // 1. Patch Name
        JPanel namePanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        namePanel.add(new JBLabel(I18n.get(project, "dialog.paste_patch.name_label")), BorderLayout.WEST);
        patchNameField = new JBTextField("custom-" + System.currentTimeMillis() % 10000 + ".patch");
        namePanel.add(patchNameField, BorderLayout.CENTER);
        panel.add(namePanel, gbc);

        // 2. Description
        gbc.gridy++;
        JPanel descPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        descPanel.add(new JBLabel(I18n.get(project, "dialog.paste_patch.desc_label")), BorderLayout.WEST);
        descriptionField = new JBTextField();
        descPanel.add(descriptionField, BorderLayout.CENTER);
        panel.add(descPanel, gbc);

        // 3. Preview
        gbc.gridy++;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        JPanel previewPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        previewPanel.add(new JBLabel(I18n.get(project, "dialog.paste_patch.preview_label")), BorderLayout.NORTH);
        previewArea = new JBTextArea(clipboardText);
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11)));
        previewPanel.add(new JBScrollPane(previewArea), BorderLayout.CENTER);
        panel.add(previewPanel, gbc);

        return panel;
    }

    @Override
    protected void doOKAction() {
        String name = patchNameField.getText().trim();
        if (name.isEmpty()) {
            Messages.showErrorDialog(project,
                    I18n.get(project, "dialog.paste_patch.empty_name_msg"),
                    I18n.get(project, "dialog.paste_patch.empty_name_title"));
            return;
        }

        try {
            GitPatchService service = GitPatchService.getInstance(project);
            createdEntry = service.createPatchFromClipboard(
                    name,
                    "",
                    descriptionField.getText().trim()
            );
            super.doOKAction();
        } catch (Exception e) {
            Messages.showErrorDialog(project,
                    I18n.get(project, "dialog.paste_patch.save_error_msg", e.getMessage()),
                    I18n.get(project, "dialog.paste_patch.save_error_title"));
        }
    }

    @Nullable
    public GitPatchEntry getCreatedEntry() {
        return createdEntry;
    }
}
