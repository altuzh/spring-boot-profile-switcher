package configswitcher.mesh.ui;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceNavigator {

    private static final Pattern STACK_LINE_PATTERN =
            Pattern.compile("^\\s*at\\s+([a-zA-Z0-9_$.]+)\\.([a-zA-Z0-9_$<>]+)\\(([^:]+):(\\d+)\\)");

    public record StackLocation(
            String fullLine,
            String className,
            String methodName,
            String fileName,
            int lineNumber
    ) {
        public boolean isUserCode() {
            return !className.startsWith("java.") &&
                    !className.startsWith("javax.") &&
                    !className.startsWith("jdk.") &&
                    !className.startsWith("sun.") &&
                    !className.startsWith("org.springframework.") &&
                    !className.startsWith("org.apache.") &&
                    !className.startsWith("org.hibernate.") &&
                    !className.startsWith("com.fasterxml.") &&
                    !className.startsWith("io.netty.") &&
                    !className.startsWith("reactor.") &&
                    !className.startsWith("graphql.") &&
                    !className.startsWith("org.eclipse.");
        }
    }

    private SourceNavigator() {}

    public static @Nullable StackLocation parseStackLine(@Nullable String line) {
        if (line == null) return null;
        Matcher m = STACK_LINE_PATTERN.matcher(line);
        if (m.find()) {
            String className = m.group(1);
            String methodName = m.group(2);
            String fileName = m.group(3);
            int lineNumber = 1;
            try {
                lineNumber = Integer.parseInt(m.group(4));
            } catch (NumberFormatException ignored) {}
            return new StackLocation(line.trim(), className, methodName, fileName, lineNumber);
        }
        return null;
    }

    public static @NotNull List<StackLocation> parseAllLocations(@Nullable String stackTrace) {
        List<StackLocation> list = new ArrayList<>();
        if (stackTrace == null || stackTrace.isBlank()) return list;

        for (String line : stackTrace.split("\n")) {
            StackLocation loc = parseStackLine(line);
            if (loc != null) {
                list.add(loc);
            }
        }
        return list;
    }

    public static boolean navigateTo(@NotNull Project project, @NotNull StackLocation loc) {
        if (project.isDisposed()) return false;

        String baseClassName = loc.className();
        if (baseClassName.contains("$")) {
            baseClassName = baseClassName.substring(0, baseClassName.indexOf('$'));
        }

        // 1. Try JavaPsiFacade in project scope
        try {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
            PsiClass psiClass = facade.findClass(baseClassName, projectScope);
            if (psiClass == null) {
                psiClass = facade.findClass(baseClassName, GlobalSearchScope.allScope(project));
            }
            if (psiClass != null && psiClass.getContainingFile() != null) {
                VirtualFile vFile = psiClass.getContainingFile().getVirtualFile();
                if (vFile != null && vFile.isValid()) {
                    new OpenFileDescriptor(project, vFile, Math.max(0, loc.lineNumber() - 1), 0).navigate(true);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // 2. Fallback: Search by fileName in project scope
        try {
            GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
            var files = FilenameIndex.getVirtualFilesByName(loc.fileName(), projectScope);
            if (files.isEmpty()) {
                files = FilenameIndex.getVirtualFilesByName(loc.fileName(), GlobalSearchScope.allScope(project));
            }
            if (!files.isEmpty()) {
                VirtualFile vFile = files.iterator().next();
                if (vFile != null && vFile.isValid()) {
                    new OpenFileDescriptor(project, vFile, Math.max(0, loc.lineNumber() - 1), 0).navigate(true);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
