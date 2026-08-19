package com.scanner;

import io.github.springwolf.asyncapi.v3.jackson.DefaultAsyncApiSerializerService;
import io.github.springwolf.asyncapi.v3.model.AsyncAPI;
import io.github.springwolf.core.standalone.DefaultStandaloneApplication;
import io.github.springwolf.core.standalone.StandaloneApplication;
import io.github.springwolf.core.standalone.StandaloneEnvironmentLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Заставляет Springwolf просканировать классы ЧУЖОГО jar, лежащие на classpath
 * (BOOT-INF/classes распакованного fat-jar). application.yml жертвы подхватывается
 * спринговым ConfigData с того же classpath — потому резолвятся ${...}-топики.
 *
 * args[0] — base-package жертвы, args[1] — путь к asyncapi.json, args[2] — title.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        String basePackage = args.length > 0 ? args[0] : "com.example";
        Path out = Path.of(args.length > 1 ? args[1] : "asyncapi.json");
        String title = args.length > 2 ? args[2] : basePackage;

        ConfigurableEnvironment environment = StandaloneEnvironmentLoader.load();
        environment.getPropertySources().addFirst(new MapPropertySource("scanner", Map.of(
                "springwolf.docket.base-package", basePackage,
                "springwolf.docket.info.title", title + " (reconstructed)",
                "springwolf.docket.info.version", "0.0.1",
                "springwolf.docket.servers.kafka.protocol", "kafka",
                "springwolf.docket.servers.kafka.host", "unknown:9092",
                "springwolf.docket.servers.amqp.protocol", "amqp",
                "springwolf.docket.servers.amqp.host", "unknown:5672")));

        StandaloneApplication standaloneApplication = DefaultStandaloneApplication.builder()
                .setEnvironment(environment)
                .buildAndStart();

        AsyncAPI asyncApi = standaloneApplication.getAsyncApiService().getAsyncAPI();
        String json = new DefaultAsyncApiSerializerService().toJsonString(asyncApi);
        Files.writeString(out, json);
        System.out.println("WROTE " + out.toAbsolutePath());
    }
}
