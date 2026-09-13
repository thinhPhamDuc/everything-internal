package com.app.internal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Task 9: bật quét @ConfigurationProperties (VD SyncProviderProperties) mà
// không cần khai từng @EnableConfigurationProperties riêng lẻ ở mỗi nơi dùng.
@SpringBootApplication
@ConfigurationPropertiesScan
public class EverythingInternalApplication {

    public static void main(String[] args) {
        SpringApplication.run(EverythingInternalApplication.class, args);
    }

}
