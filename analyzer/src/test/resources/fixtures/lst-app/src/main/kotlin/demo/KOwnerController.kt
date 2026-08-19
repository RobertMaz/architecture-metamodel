package demo

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/k")
class KOwnerController {

    @GetMapping("/owners/{ownerId}")
    fun owner(ownerId: String): String = ownerId
}
