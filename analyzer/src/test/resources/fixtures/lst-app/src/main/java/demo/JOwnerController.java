package demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/j")
public class JOwnerController {

    @GetMapping("/owners/{ownerId}")
    public String owner(String ownerId) {
        return ownerId;
    }
}
