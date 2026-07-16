package com.zsc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.zsc.mapper")
@EnableScheduling
public class MycodeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MycodeAgentApplication.class, args);
    }

}
