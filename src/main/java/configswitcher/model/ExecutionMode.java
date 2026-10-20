package configswitcher.model;

public enum ExecutionMode {
    COMMAND_PIPELINE(
            "Command Pipeline (Pre-run + Run)",
            "Executes configurable Pre-Run command (e.g. Maven build) followed by Run command (e.g. java -jar)"
    ),
    INTELLIJ_RUN_CONFIG(
            "IntelliJ Run Configuration",
            "Updates program arguments and VM options on an existing IDE Run Configuration"
    );

    private final String title;
    private final String description;

    ExecutionMode(String title, String description) {
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
