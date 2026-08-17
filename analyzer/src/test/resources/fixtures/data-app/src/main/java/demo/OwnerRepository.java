package demo;

import org.springframework.data.jpa.repository.JpaRepository;

interface OwnerRepository extends JpaRepository<Owner, Integer> {
}

class Owner {
}
