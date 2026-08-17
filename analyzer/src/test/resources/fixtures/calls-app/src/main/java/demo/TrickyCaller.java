package demo;

import org.springframework.web.client.RestTemplate;

/** URL собирается из поля и метода — статике не по зубам, это точка внимания для LLM. */
class TrickyCaller {

    private RestTemplate rest;
    private String base;

    Object fetch() {
        return rest.postForObject(base + buildPath(), null, Object.class);
    }

    private String buildPath() {
        return "/api/v1/refunds";
    }
}
