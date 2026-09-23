package com.wordreview.repository;

import com.wordreview.entity.WordGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WordGroupRepository extends JpaRepository<WordGroup, Long> {

    Optional<WordGroup> findByCode(String code);

    boolean existsByCode(String code);

    List<WordGroup> findByParentId(Long parentId);

    boolean existsByParentId(Long parentId);
}
