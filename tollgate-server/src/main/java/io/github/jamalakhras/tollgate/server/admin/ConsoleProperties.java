package io.github.jamalakhras.tollgate.server.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tollgate.console")
public record ConsoleProperties(@DefaultValue("true") boolean enabled) {}
