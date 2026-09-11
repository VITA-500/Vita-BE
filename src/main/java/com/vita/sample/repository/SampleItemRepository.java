package com.vita.sample.repository;

import com.vita.sample.entity.SampleItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SampleItemRepository extends JpaRepository<SampleItem, Long> {

	Page<SampleItem> findByTitleContainingIgnoreCase(String keyword, Pageable pageable);
}
