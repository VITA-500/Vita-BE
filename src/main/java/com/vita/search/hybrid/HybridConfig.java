package com.vita.search.hybrid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code search.hybrid.enabled=true}일 때만 Hybrid 설정을 읽는다. 꺼져 있으면 Hybrid 관련 빈이 하나도 만들어지지 않는다. */
@Configuration
@ConditionalOnProperty(prefix = "search.hybrid", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(HybridProperties.class)
public class HybridConfig {
}
