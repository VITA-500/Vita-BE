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
	
	// ai.bedrock.aws.region(AWS_REGION 환경변수로 제어, application.yml)과 같은 값을 재사용한다.
	// 여기서 별도 프로퍼티(bedrock.region)를 새로 만들면 두 설정이 어긋날 위험이 있고,
	// 기본값도 없어 값이 안 채워지면 기동 자체가 실패한다.
	@Value("${ai.bedrock.aws.region}")
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