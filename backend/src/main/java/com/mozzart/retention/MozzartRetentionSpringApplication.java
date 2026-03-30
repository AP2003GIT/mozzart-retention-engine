package com.mozzart.retention;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MozzartRetentionSpringApplication {
  public static void main(String[] args) {
    SpringApplication.run(MozzartRetentionSpringApplication.class, args);
  }
}
