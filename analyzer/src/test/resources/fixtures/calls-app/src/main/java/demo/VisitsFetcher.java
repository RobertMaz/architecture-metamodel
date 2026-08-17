package demo;

import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

class VisitsFetcher {

    private WebClient.Builder wcb;
    private RestTemplate rest;

    Object visits() {
        return wcb.build().get().uri("http://visits-service/pets/visits?petId={id}", 1).retrieve();
    }

    Object owner(int id) {
        return rest.getForObject("http://customers-service/owners/" + id, Object.class);
    }
}
