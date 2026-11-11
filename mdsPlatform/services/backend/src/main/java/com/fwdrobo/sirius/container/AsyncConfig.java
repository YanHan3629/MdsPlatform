package com.fwdrobo.sirius.container;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {
 private final int core,max,capacity;
 public AsyncConfig(@Value("${app.job-launcher.core-pool-size:4}") int core,
   @Value("${app.job-launcher.max-pool-size:16}") int max,
   @Value("${app.job-launcher.queue-capacity:100}") int capacity){
  if(core<1||max<core||max>16||capacity<1||capacity>10000)
   throw new IllegalArgumentException("Invalid job launcher executor limits");
  this.core=core;this.max=max;this.capacity=capacity;
 }
 @Bean(name="jobLauncherExecutor")
 public Executor jobLauncherExecutor(){
  var executor=new ThreadPoolTaskExecutor();
  executor.setCorePoolSize(core);executor.setMaxPoolSize(max);executor.setQueueCapacity(capacity);
  executor.setThreadNamePrefix("job-launcher-");executor.initialize();return executor;
 }
}
