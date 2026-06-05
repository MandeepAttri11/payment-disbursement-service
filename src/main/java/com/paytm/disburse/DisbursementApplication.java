package com.paytm.disburse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DisbursementApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisbursementApplication.class, args);
    }
}
