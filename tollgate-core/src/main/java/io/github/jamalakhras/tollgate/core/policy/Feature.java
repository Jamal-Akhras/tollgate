package io.github.jamalakhras.tollgate.core.policy;

public enum Feature {
    REMOTE_IMAGES("images by URL"),
    REMOTE_DOCUMENTS("documents by URL"),
    PROVIDER_FILES("files held by the provider");

    private final String description;

    Feature(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
