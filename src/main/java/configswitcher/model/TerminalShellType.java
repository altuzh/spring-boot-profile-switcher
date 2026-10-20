package configswitcher.model;

/**
 * Supported terminal shell types for command execution and chaining syntax.
 */
public enum TerminalShellType {
    AUTO(
            "Auto-detect (Default)",
            "Automatically detects active shell; defaults to PowerShell on Windows"
    ),
    POWERSHELL(
            "PowerShell / pwsh",
            "Uses PowerShell command syntax (Set-Location, cmd1; if ($?) { cmd2 })"
    ),
    CMD(
            "Command Prompt (cmd.exe)",
            "Uses CMD syntax (cd /d, cmd1 && cmd2)"
    ),
    BASH(
            "Bash / Zsh / WSL",
            "Uses POSIX shell syntax (cd, cmd1 && cmd2)"
    );

    private final String title;
    private final String description;

    TerminalShellType(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return title;
    }
}
