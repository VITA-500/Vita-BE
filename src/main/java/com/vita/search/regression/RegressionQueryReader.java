package com.vita.search.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** classpath의 JSONL 파일을 한 줄씩 읽어 지정한 타입으로 역직렬화한다. */
@Component
public class RegressionQueryReader {

	private final ObjectMapper objectMapper;

	public RegressionQueryReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public <T> List<T> read(Resource resource, Class<T> type) {
		List<T> items = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				items.add(objectMapper.readValue(line, type));
			}
		} catch (IOException exception) {
			throw new IllegalStateException("회귀 테스트 질문 파일을 읽을 수 없습니다: " + resource.getDescription(), exception);
		}
		return items;
	}
}
