package com.vita.faq.collection;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/** LG U+ FAQ 상세 답변의 HTML을 JSONL에 저장할 일반 텍스트로 변환한다. */
@Component
public class LguFaqHtmlCleaner {

	private static final String LINE_BREAK_MARKER = "\uE000";
	private static final Set<String> BLOCK_TAGS = Set.of(
		"p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr"
	);
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern SPACES_AROUND_LINE_BREAK = Pattern.compile(" *\\n *");
	private static final Pattern EXCESSIVE_LINE_BREAKS = Pattern.compile("\\n{3,}");

	public String clean(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}

		StringBuilder text = new StringBuilder();
		NodeTraversor.traverse(new NodeVisitor() {
			@Override
			public void head(Node node, int depth) {
				if (node instanceof TextNode textNode) {
					text.append(textNode.getWholeText());
				} else if (node instanceof Element element && element.normalName().equals("br")) {
					text.append(LINE_BREAK_MARKER);
				}
			}

			@Override
			public void tail(Node node, int depth) {
				if (node instanceof Element element && BLOCK_TAGS.contains(element.normalName())) {
					text.append(LINE_BREAK_MARKER);
				}
			}
		}, Jsoup.parseBodyFragment(html).body());

		return normalize(text.toString());
	}

	private String normalize(String text) {
		String normalized = text
			.replace('\u00A0', ' ')
			.replace("\r\n", " ")
			.replace('\r', ' ')
			.replace('\n', ' ');
		normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
		normalized = normalized.replaceAll(" *" + LINE_BREAK_MARKER + " *", "\n");
		normalized = SPACES_AROUND_LINE_BREAK.matcher(normalized).replaceAll("\n");
		normalized = EXCESSIVE_LINE_BREAKS.matcher(normalized).replaceAll("\n\n");
		return normalized.strip();
	}
}
