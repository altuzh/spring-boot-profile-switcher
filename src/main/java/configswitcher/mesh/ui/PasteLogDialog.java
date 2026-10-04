package configswitcher.mesh.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.mesh.service.MeshCaptureService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;

public class PasteLogDialog extends DialogWrapper {

    private final Project project;
    private final JTextArea textArea = new JTextArea();

    public PasteLogDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        setTitle(I18n.get(project, "mesh.dialog.paste.title"));
        setOKButtonText(I18n.get(project, "mesh.dialog.paste.button"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(JBUI.size(650, 400));

        JBLabel instructionLabel = new JBLabel(I18n.get(project, "mesh.dialog.paste.instruction"));
        panel.add(instructionLabel, BorderLayout.NORTH);

        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(false);
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);

        return panel;
    }

    @Override
    protected void doOKAction() {
        String text = textArea.getText();
        if (text != null && !text.isBlank()) {
            var analyzer = MeshCaptureService.getInstance(project).getAnalyzer();
            String[] lines = text.split("\r?\n");
            for (String line : lines) {
                analyzer.processLine(line);
            }
        }
        super.doOKAction();
    }
}
