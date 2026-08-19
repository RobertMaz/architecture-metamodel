package demo;

public class JCaller {
    static final String BASE = "http://customers-service";

    void fetch(Object webClient) {
        // untyped без classpath: цепочка create(...).get().uri(...)
        org.springframework.web.reactive.function.client.WebClient.create(BASE)
            .get()
            .uri(BASE + "/owners/1")
            .retrieve();
    }
}
