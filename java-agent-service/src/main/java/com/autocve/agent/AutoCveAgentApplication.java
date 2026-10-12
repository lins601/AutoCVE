package com.autocve.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AutoCveAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoCveAgentApplication.class, args);
    }
}
