package com.autocve.agent.workflow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuditWorkflowExecutorConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService auditWorkflowExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
