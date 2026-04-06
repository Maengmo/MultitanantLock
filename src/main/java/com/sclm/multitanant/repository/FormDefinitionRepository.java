package com.sclm.multitanant.repository;

import com.sclm.multitanant.entity.FormDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormDefinitionRepository extends JpaRepository<FormDefinition, Long> {

    List<FormDefinition> findByTenantIdAndFormTypeOrderByOrderNoAsc(String tenantId, String formType);

    boolean existsByTenantId(String tenantId);
}
