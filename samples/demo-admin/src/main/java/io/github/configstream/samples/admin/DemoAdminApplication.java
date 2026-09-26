package io.github.configstream.samples.admin;

import io.github.configstream.admin.EnableConfigStreamAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The admin server as a user would run it: their own Spring Boot app plus one annotation. */
@SpringBootApplication
@EnableConfigStreamAdminServer
public class DemoAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoAdminApplication.class, args);
    }
}
