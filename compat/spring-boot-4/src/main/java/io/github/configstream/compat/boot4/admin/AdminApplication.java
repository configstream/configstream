package io.github.configstream.compat.boot4.admin;

import io.github.configstream.admin.EnableConfigStreamAdminServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A Spring Boot 4 admin server: any web app plus the annotation. */
@SpringBootApplication
@EnableConfigStreamAdminServer
public class AdminApplication {
}
