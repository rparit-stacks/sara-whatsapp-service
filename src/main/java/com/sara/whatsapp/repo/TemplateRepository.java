package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.Template;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface TemplateRepository extends MongoRepository<Template, String> {
    Optional<Template> findByKey(String key);

    Optional<Template> findFirstByMetaName(String metaName);

    Optional<Template> findByMetaTemplateId(String metaTemplateId);

    Optional<Template> findByMetaNameAndLanguage(String metaName, String language);
}
