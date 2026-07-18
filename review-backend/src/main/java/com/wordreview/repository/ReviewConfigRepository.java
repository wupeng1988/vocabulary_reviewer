package com.wordreview.repository;

import com.wordreview.entity.ReviewConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewConfigRepository extends JpaRepository<ReviewConfig, Long> {
}
