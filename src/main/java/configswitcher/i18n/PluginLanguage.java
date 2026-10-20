package configswitcher.i18n;

public enum PluginLanguage {
    EN("English", "en"),
    RU("Русский", "ru");

    private final String displayName;
    private final String code;

    PluginLanguage(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static PluginLanguage fromCode(String code) {
        if (code != null) {
            for (PluginLanguage lang : values()) {
                if (lang.code.equalsIgnoreCase(code) || lang.name().equalsIgnoreCase(code)) {
                    return lang;
                }
            }
        }
        return EN;
    }

    public static PluginLanguage detectSystemLanguage() {
        try {
            java.util.Locale locale = java.util.Locale.getDefault();
            if (locale != null) {
                String lang = locale.getLanguage();
                if (lang != null && lang.toLowerCase().startsWith("ru")) {
                    return RU;
                }
            }
        } catch (Throwable ignored) {}
        return EN;
    }
}
