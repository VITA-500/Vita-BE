package com.vita.store.controller;

import com.vita.store.dto.request.StoreCreateRequest;
import com.vita.store.dto.request.StoreUpdateRequest;
import com.vita.store.dto.response.StoreCreateResponse;
import com.vita.store.dto.response.StoreDeleteResponse;
import com.vita.store.dto.response.StoreUpdateResponse;
import com.vita.store.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final StoreService storeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreCreateResponse create(@Valid @RequestBody StoreCreateRequest request){
        return storeService.create(request);
    }

    @PatchMapping("/{storeId}")
    public StoreUpdateResponse update(@PathVariable Long storeId, @Valid @RequestBody StoreUpdateRequest request){
        return storeService.update(storeId, request);
    }

    @DeleteMapping("/{storeId}")
    public StoreDeleteResponse delete(@PathVariable Long storeId){
        return storeService.delete(storeId);
    }
}
