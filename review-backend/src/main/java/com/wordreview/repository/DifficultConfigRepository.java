package com.wordreview.repository;

import com.wordreview.entity.DifficultConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DifficultConfigRepository extends JpaRepository<DifficultConfig, Long> {
}
