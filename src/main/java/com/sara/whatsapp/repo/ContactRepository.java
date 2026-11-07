package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.Contact;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ContactRepository extends MongoRepository<Contact, String> {

    Optional<Contact> findByPhone(String phone);
}
