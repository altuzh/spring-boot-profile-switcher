package configswitcher.model;

public enum SwitchMode {
    PROFILE_ARGUMENTS(
            "Profile / Parameter Mode (Non-destructive)",
            "Passes --spring.profiles.active or --spring.config.additional-location to the run configuration and restarts the app."
    ),
    FILE_SWAP(
            "File Swap Mode",
            "Replaces application.yml with the selected application-<profile>.yml (backing up the original to .bak)."
    );

    private final String title;
    private final String description;

    SwitchMode(String title, String description) {
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
