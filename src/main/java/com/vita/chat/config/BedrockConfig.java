package com.vita.chat.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class BedrockConfig {
	
	@Value("${bedrock.region}")
    private String bedrockRegion;

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.builder()
        		.region(Region.of(bedrockRegion))
        		.httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))  // 연결 자체가 안 될 때
                        .socketTimeout(Duration.ofSeconds(15)))    // 연결은 됐는데 응답이 안 올 때
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))       // 재시도 포함 전체 제한 시간
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)) // 단일 시도당 제한 시간
                        .build())
                .build();
    }
}