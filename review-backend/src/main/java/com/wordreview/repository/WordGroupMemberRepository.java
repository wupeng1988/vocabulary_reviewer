package com.wordreview.repository;

import com.wordreview.entity.WordGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WordGroupMemberRepository extends JpaRepository<WordGroupMember, Long> {

    List<WordGroupMember> findByWordId(Long wordId);

    List<WordGroupMember> findByWordIdIn(List<Long> wordIds);

    List<WordGroupMember> findByGroupId(Long groupId);

    List<WordGroupMember> findByGroupIdIn(List<Long> groupIds);

    boolean existsByWordIdAndGroupId(Long wordId, Long groupId);

    long countByGroupId(Long groupId);

    void deleteByWordId(Long wordId);

    void deleteByGroupId(Long groupId);

    void deleteByWordIdIn(List<Long> wordIds);
}
