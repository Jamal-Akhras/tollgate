package io.github.jamalakhras.tollgate.gateway;

import io.github.jamalakhras.tollgate.core.config.WireFormat;

final class Endpoints {

    static final String OPENAI_CHAT = "/v1/chat/completions";
    static final String OPENAI_MODELS = "/v1/models";
    static final String ANTHROPIC_MESSAGES = "/anthropic/v1/messages";

    private Endpoints() {}

    static String pathFor(WireFormat format) {
        return switch (format) {
            case OPENAI -> OPENAI_CHAT;
            case ANTHROPIC -> ANTHROPIC_MESSAGES;
        };
    }
}
