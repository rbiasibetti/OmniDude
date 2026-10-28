package com.omnidude.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    /** Executor das checagens: uma virtual thread por execução (I/O bloqueante). */
    @Bean(destroyMethod = "close")
    public ExecutorService checkExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("check-", 0).factory());
    }
}
