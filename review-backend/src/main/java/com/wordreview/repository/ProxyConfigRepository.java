package com.wordreview.repository;

import com.wordreview.entity.ProxyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProxyConfigRepository extends JpaRepository<ProxyConfig, Long> {
}
