package com.vita.plan.dto;

import java.time.LocalDateTime;

public record PlanUpdateResponse(long planId, LocalDateTime updatedAt) { }
