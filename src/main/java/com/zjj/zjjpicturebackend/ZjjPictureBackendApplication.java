package com.zjj.zjjpicturebackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@MapperScan("com.zjj.zjjpicturebackend.mapper")
@SpringBootApplication
@EnableAsync
public class ZjjPictureBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZjjPictureBackendApplication.class, args);
    }

}
