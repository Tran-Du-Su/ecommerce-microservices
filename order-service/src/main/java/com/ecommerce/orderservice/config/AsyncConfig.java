package com.ecommerce.orderservice.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    @Bean(name = "externalCallExecutor")
    public Executor externalCallExecutor() {
        return Executors.newFixedThreadPool(10);
    }
}
