package dev.vatsal.upiswitch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SwitchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SwitchApplication.class, args);
    }
}
