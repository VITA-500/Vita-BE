package com.vita.store.service;

import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitResponse;
import com.vita.store.repository.BenefitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BenefitService {

    private final BenefitRepository benefitRepository;

    public BenefitListResponse findAll(String category){
        List<BenefitResponse> benefits = benefitRepository.findAllWithStoreCount();
        if(category != null && !category.isBlank()){
            String target = category.trim();
            benefits = benefits.stream().filter(benefit -> benefit.category().equals(target)).toList();
        }
        return new BenefitListResponse(benefits);
    }
}
