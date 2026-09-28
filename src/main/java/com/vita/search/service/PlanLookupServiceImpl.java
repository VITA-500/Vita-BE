package com.vita.search.service;

import com.vita.search.dto.PlanReference;
import com.vita.search.repository.PlanLookupRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link PlanLookupService}의 실제 구현. */
@Service
public class PlanLookupServiceImpl implements PlanLookupService {

	private final PlanLookupRepository planLookupRepository;

	public PlanLookupServiceImpl(PlanLookupRepository planLookupRepository) {
		this.planLookupRepository = planLookupRepository;
	}

	@Override
	public List<PlanReference> findExtreme(PlanSortKey sortKey, int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("limit은 1 이상이어야 합니다.");
		}
		return planLookupRepository.findByExtreme(sortKey, limit);
	}
}
