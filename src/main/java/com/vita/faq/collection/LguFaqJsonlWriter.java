package com.vita.faq.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.faq.batch.FaqJsonlRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

/** 정제된 LG U+ FAQ를 기존 DB 적재기가 읽을 수 있는 JSONL 파일로 저장한다. */
@Component
public class LguFaqJsonlWriter {

	private static final Comparator<LguFaqCollectedRecord> OUTPUT_ORDER = Comparator
		.comparing(LguFaqCollectedRecord::category)
		.thenComparing(LguFaqCollectedRecord::subcategory)
		.thenComparing(LguFaqCollectedRecord::sourceFaqId);

	private final ObjectMapper objectMapper;

	public LguFaqJsonlWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 이전 실행이 남긴 JSONL을 읽어 중단 지점부터 수집할 수 있게 한다. */
	public List<LguFaqCollectedRecord> readExisting(Path output) {
		if (output == null || Files.notExists(output)) {
			return List.of();
		}
		List<LguFaqCollectedRecord> records = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				FaqJsonlRecord faq = objectMapper.readValue(line, FaqJsonlRecord.class);
				records.add(new LguFaqCollectedRecord(
					faq.stableId(), faq.category(), faq.subcategory(), faq.question(), faq.answer(), ""
				));
			}
			return List.copyOf(records);
		} catch (IOException exception) {
			throw new LguFaqCollectionException("기존 FAQ JSONL 파일을 읽을 수 없습니다: " + output, exception);
		}
	}

	public void write(Path output, List<LguFaqCollectedRecord> faqs) {
		if (output == null) {
			throw new IllegalArgumentException("FAQ JSONL 출력 경로가 필요합니다.");
		}
		if (faqs == null || faqs.isEmpty()) {
			throw new IllegalArgumentException("FAQ JSONL에 저장할 데이터가 없습니다.");
		}

		createParentDirectory(output);
		List<LguFaqCollectedRecord> sortedFaqs = faqs.stream().sorted(OUTPUT_ORDER).toList();
		try (BufferedWriter writer = Files.newBufferedWriter(
			output,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)) {
			for (LguFaqCollectedRecord faq : sortedFaqs) {
				writer.write(objectMapper.writeValueAsString(toJsonlRecord(faq)));
				writer.newLine();
			}
		} catch (IOException exception) {
			throw new LguFaqCollectionException("FAQ JSONL 파일을 저장할 수 없습니다: " + output, exception);
		}
	}

	private FaqJsonlRecord toJsonlRecord(LguFaqCollectedRecord faq) {
		return new FaqJsonlRecord(
			faq.sourceFaqId(),
			faq.category(),
			faq.subcategory(),
			faq.question(),
			faq.answer(),
			List.of(faq.sourceFaqId())
		);
	}

	private void createParentDirectory(Path output) {
		Path parent = output.toAbsolutePath().getParent();
		if (parent == null) {
			return;
		}
		try {
			Files.createDirectories(parent);
		} catch (IOException exception) {
			throw new LguFaqCollectionException("FAQ JSONL 출력 디렉터리를 만들 수 없습니다: " + parent, exception);
		}
	}
}
