package demo;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

@RestController
@RequestMapping("/owners")
class OwnerController {

    @GetMapping("/{ownerId}")
    public OwnerDto findOwner(@PathVariable int ownerId) {
        return null;
    }

    @PostMapping
    public ResponseEntity<OwnerDto> create(@RequestBody OwnerDto body,
            @RequestParam(required = false) String source) {
        return null;
    }

    @Deprecated
    @GetMapping("/legacy")
    public List<OwnerDto> legacy() {
        return null;
    }
}
