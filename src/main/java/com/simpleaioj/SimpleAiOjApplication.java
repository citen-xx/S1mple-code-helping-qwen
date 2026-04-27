package com.simpleaioj;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.simpleaioj.mapper")
@SpringBootApplication
public class SimpleAiOjApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimpleAiOjApplication.class, args);
    }
}
