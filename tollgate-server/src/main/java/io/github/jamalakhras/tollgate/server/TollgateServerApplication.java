package io.github.jamalakhras.tollgate.server;

import io.github.jamalakhras.tollgate.server.cli.Commands;
import java.security.SecureRandom;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TollgateServerApplication {

    public static void main(String[] args) {
        if (Commands.isCommand(args)) {
            System.exit(new Commands(System.out, System.err, new SecureRandom()).run(List.of(args)));
        }
        SpringApplication.run(TollgateServerApplication.class, args);
    }
}
