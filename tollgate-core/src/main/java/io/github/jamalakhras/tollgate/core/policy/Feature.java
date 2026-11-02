package io.github.jamalakhras.tollgate.core.policy;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum Feature {
    REMOTE_IMAGES("remote-images", "images by URL"),
    REMOTE_DOCUMENTS("remote-documents", "documents by URL"),
    PROVIDER_FILES("provider-files", "files held by the provider"),
    WEB_SEARCH("web-search", "web search"),
    UNREVIEWED_FIELDS("unreviewed-fields", "fields and betas Tollgate has not reviewed");

    private final String configName;
    private final String description;

    Feature(String configName, String description) {
        this.configName = configName;
        this.description = description;
    }

    public String configName() {
        return configName;
    }

    public String description() {
        return description;
    }

    public static Feature fromConfigName(String name) {
        return Arrays.stream(values())
                .filter(feature -> feature.configName.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("feature must be one of "
                        + Arrays.stream(values()).map(Feature::configName).collect(Collectors.joining(", "))
                        + ", was " + name));
    }
}
