package com.reeldown;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ReelDownloaderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReelDownloaderApplication.class, args);
    }
}
