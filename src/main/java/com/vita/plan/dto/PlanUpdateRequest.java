package com.vita.plan.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;

/** PATCH 요청에서 필드 생략과 선택 필드의 명시적인 null을 구분한다. */
@Getter
public class PlanUpdateRequest {

    @Size(max = 50)
    private String planCode;
    @Size(max = 100)
    private String name;
    @Size(max = 255)
    private String summary;
    @PositiveOrZero
    private Integer price;
    @Size(max = 20)
    private String networkType;
    @Size(max = 20)
    private String targetGroup;
    @PositiveOrZero
    private Integer minAge;
    @PositiveOrZero
    private Integer maxAge;
    @Size(max = 20)
    private String dataPolicy;
    @Positive
    private Long baseDataMb;
    @Positive
    private Integer exhaustedSpeedKbps;
    @Size(max = 20)
    private String voicePolicy;
    @Positive
    private Integer voiceMinutes;
    @Size(max = 20)
    private String smsPolicy;
    @Positive
    private Integer smsCount;
    private String description;
    @Size(max = 20)
    private String status;

    @JsonIgnore
    private final Set<String> provided = new HashSet<>();

    @JsonSetter public void setPlanCode(String value) { planCode = value; provided.add("planCode"); }
    @JsonSetter public void setName(String value) { name = value; provided.add("name"); }
    @JsonSetter public void setSummary(String value) { summary = value; provided.add("summary"); }
    @JsonSetter public void setPrice(Integer value) { price = value; provided.add("price"); }
    @JsonSetter public void setNetworkType(String value) { networkType = value; provided.add("networkType"); }
    @JsonSetter public void setTargetGroup(String value) { targetGroup = value; provided.add("targetGroup"); }
    @JsonSetter public void setMinAge(Integer value) { minAge = value; provided.add("minAge"); }
    @JsonSetter public void setMaxAge(Integer value) { maxAge = value; provided.add("maxAge"); }
    @JsonSetter public void setDataPolicy(String value) { dataPolicy = value; provided.add("dataPolicy"); }
    @JsonSetter public void setBaseDataMb(Long value) { baseDataMb = value; provided.add("baseDataMb"); }
    @JsonSetter public void setExhaustedSpeedKbps(Integer value) { exhaustedSpeedKbps = value; provided.add("exhaustedSpeedKbps"); }
    @JsonSetter public void setVoicePolicy(String value) { voicePolicy = value; provided.add("voicePolicy"); }
    @JsonSetter public void setVoiceMinutes(Integer value) { voiceMinutes = value; provided.add("voiceMinutes"); }
    @JsonSetter public void setSmsPolicy(String value) { smsPolicy = value; provided.add("smsPolicy"); }
    @JsonSetter public void setSmsCount(Integer value) { smsCount = value; provided.add("smsCount"); }
    @JsonSetter public void setDescription(String value) { description = value; provided.add("description"); }
    @JsonSetter public void setStatus(String value) { status = value; provided.add("status"); }

    public boolean has(String field) {
        return provided.contains(field);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new IllegalArgumentException("수정 요청에 허용되지 않는 필드입니다: " + name);
    }
}
