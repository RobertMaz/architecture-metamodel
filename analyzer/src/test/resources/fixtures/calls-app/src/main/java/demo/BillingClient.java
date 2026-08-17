package demo;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "billing", url = "${billing.url}")
interface BillingClient {

    @PostMapping("/api/v1/invoices")
    Object create(@RequestBody Object rq);
}
