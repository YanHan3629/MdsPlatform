package com.fwdrobo.sirius;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@MapperScan("com.fwdrobo.sirius.mapper")
public class SiriusApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiriusApplication.class,args);
    }
}