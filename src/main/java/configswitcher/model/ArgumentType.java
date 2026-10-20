package configswitcher.model;

public enum ArgumentType {
    SPRING_PROFILES_ACTIVE("--spring.profiles.active=<profile>"),
    SPRING_CONFIG_ADDITIONAL_LOCATION("--spring.config.additional-location=<filepath>"),
    BOTH("Both parameters");

    private final String title;

    ArgumentType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    @Override
    public String toString() {
        return title;
    }
}
