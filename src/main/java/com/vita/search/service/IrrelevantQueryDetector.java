package com.vita.search.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질문 문장을 규칙(정규식)만으로 훑어 FAQ 검색 대상이 아닐 가능성이 높은 유형(개인 정보 조회, 타사 관련)을
 * 판별한다. LLM 호출 없이 상태 없는 순수 함수라 DB 없이 단위 테스트가 된다.
 *
 * <p>{@code RetrievalPipeline}이 {@code retrieval.irrelevant-rule.enabled}가 켜져 있을 때 원문 질문에 적용하고, 걸리면
 * FAQ·요금제 컨텍스트를 비워 BE4에 "관련 없음"으로 전달한다. 규칙을 고칠 때는 "무관 질문을 얼마나 잡는지"와
 * "정상 질문을 얼마나 잘못 잡는지"를 함께 본다(회귀 러너의 규칙 검증 세트, 평가셋 질문, FAQ 질문 원문). 그래서 결과를
 * 하나의 판정이 아니라 규칙 단위({@link Rule})로 돌려준다.
 */
public final class IrrelevantQueryDetector {

	/** 판별 규칙 종류. */
	public enum Rule {
		/** "내 요금 얼마 나왔어?"처럼 본인 계정의 실제 데이터를 묻는 질문. */
		PERSONAL_LOOKUP,
		/** SKT·KT·LG유플러스·알뜰폰·통신 3사처럼 구체적인 타사 이름이 들어간 질문. */
		COMPETITOR_BRAND,
		/** "타사", "다른 통신사"처럼 일반적인 타사 표현. 번호이동 같은 정상 질문에도 나올 수 있어 따로 측정한다. */
		COMPETITOR_GENERIC
	}

	/**
	 * 1인칭 표현. 조사가 붙은 "내가/제가/나는/저는"도 포함하고, "우리"는 "우리 동네"처럼 지역을 뜻해서 제외한다.
	 * "나"·"저"는 "큰일 나?"처럼 동사로도 쓰여서, 문장 맨 앞이거나 "이번·지금" 같은 시점 표현 앞에 올 때만 1인칭으로 본다.
	 */
	private static final Pattern FIRST_PERSON = Pattern.compile(
			"(?<![가-힣])(?:내가|제가|나는|저는|내|제)(?![가-힣])"
					+ "|(?:^\\s*|[.?!]\\s*)(?:나|저)(?![가-힣])"
					+ "|(?<![가-힣])(?:나|저)(?= ?(?:이번|지금|요즘|오늘|어제|최근|방금|지난|올해|작년|아직|벌써))");

	/**
	 * 실제 값이나 상태를 묻는 조회 표현. "내역/이력"은 "청구 내역이 제 내역과 함께 보여요"처럼 문제 상황을
	 * 설명하는 정상 질문에도 나와서 단독으로는 넣지 않았다("내역 보여줘"는 "보여줘"로 잡힌다).
	 */
	private static final Pattern LOOKUP = Pattern.compile(
			"얼마|몇|언제|뭐야|뭐예요|뭔가요|뭔지|뭐지|뭐죠|뭐로|상태|보여줘|남았|나왔|썼|쓰고 ?있|받고 ?있|가입(?:된|한|돼|되어)|등록(?:된|돼|되)"
					+ "|만료|현황|알려줘|알려 ?주세요|궁금|확인해 ?줘|확인해 ?주세요|조회해 ?(?:줘|주세요)|잔액");

	/**
	 * 소유형 1인칭("내 요금", "제 데이터"). 뒤에 한글이 붙지 않는 "내/제"만 해당하고, "내가/제가"는 주어형이라 포함하지 않는다.
	 * 소유형은 "내 ○○"이라고 본인 소유를 직접 가리키므로 조회 표현만 있어도 본인 기록을 묻는 것으로 본다.
	 */
	private static final Pattern POSSESSIVE = Pattern.compile("(?<![가-힣])(?:내|제)(?![가-힣])");

	/**
	 * 주어형 1인칭("나는/저는/내가/제가", 문두의 "나/저")이 쓰였을 때, 본인 기록을 묻는다고 볼 수 있는 근거. 주어형은
	 * "나는 대학생이고 데이터를 많이 써요. 요금제 알려줘"처럼 자기소개로도 쓰이므로, 조회 표현("궁금", "알려줘")만으로는
	 * 본인 기록을 묻는다고 보지 않고 기록·상태를 직접 가리키는 말이 함께 있어야 한다. "얼마", "몇"은 "월 얼마짜리가 좋아요?"처럼
	 * 가격을 묻는 말에도 쓰여서 여기에 넣지 않고, 이미 쓴 양이나 시점과 함께 있을 때만 {@link #asksOwnRecord}가 근거로 쓴다.
	 * "언제"도 "언제였지", "언제 가입했는지"처럼 지난 일을 묻는 모양일 때만 근거로 본다("언제 가입하면 할인돼?"는 아니다).
	 */
	private static final Pattern RECORD_EVIDENCE = Pattern.compile(
		"남았|나왔|썼|쓰고 ?있|받고 ?있|가입(?:된|한|돼|되어)|등록(?:된|돼|되)|상태|만료|현황|잔액|조회해|내역|이력|기록"
		+ "|언제(?: ?[가-힣]{1,6})?(?:였|했|썼|냈|됐)");

	/**
	 * 주어형 1인칭이 바로 앞에 붙어 본인이 쓰는 대상을 가리키는 표현("제가 쓰는 요금제", "내가 이용 중인 서비스").
	 * "학생들이 쓰는 요금제"처럼 다른 사람 이야기와 구분하려고 1인칭이 바로 앞에 있을 때만 잡는다.
	 */
	private static final Pattern OWN_HOLDING = Pattern.compile(
		"(?:내가|제가|나는|저는) ?(?:지금|현재)? ?(?:쓰는|쓰고 ?있는|이용하는|사용하는|(?:이용|사용|가입) ?중인|가입한|가입된)"
		+ " ?(?:요금제|부가 ?서비스|서비스|회선|번호|플랜|상품)");

	/** 값을 묻는 말. 가격을 묻는 질문에도 쓰여서 시점·이미 쓴 양과 함께 있을 때만 근거가 된다. */
	private static final Pattern VALUE_ASK = Pattern.compile("얼마|몇|언제");

	/** 어떤 질문에나 붙는 일반 요청어. 주어형 1인칭과 함께 쓰이면 자기소개 뒤의 요청일 수 있어서 이것만으로는 본인 기록 조회로 보지 않는다. */
	private static final Pattern GENERIC_REQUEST = Pattern.compile(
		"궁금|알려줘|알려 ?주세요|보여줘|확인해 ?줘|확인해 ?주세요|뭐야|뭐예요|뭔가요|뭔지|뭐지|뭐죠|뭐로");

	/**
	 * 시점 표현. "지난달 요금이 얼마 나왔는지"처럼 1인칭 없이도 본인의 사용·청구 기록을 묻는 문장에서 쓰인다.
	 * 일반 제도 설명("이번 달 요금제 가격")에도 나오므로 아래 {@link #USAGE_TOPIC}, {@link #CONSUMED}와 함께 있을 때만 쓴다.
	 */
	private static final Pattern PERIOD = Pattern.compile(
			"이번 ?달|지난 ?달|저번 ?달|지난 ?주|이번 ?주|이번 ?분기|오늘|어제|그저께|최근|올해|작년|현재|지금|[0-9]{1,2} ?월");

	/** 사용·청구 기록의 대상. */
	private static final Pattern USAGE_TOPIC = Pattern.compile(
			"통화|문자|데이터|요금|청구|사용량|로밍|금액|결제|납부|이용료|포인트|멤버십");

	/**
	 * 이미 쓴 양이나 청구된 금액을 가리키는 과거·결과 표현. 단순히 "얼마"만 있으면 가격을 묻는 질문일 수 있어서
	 * ("이번 달 요금제 가격은 얼마인가요?") 이 표현이 함께 있을 때만 본인 기록으로 본다.
	 */
	private static final Pattern CONSUMED = Pattern.compile(
			"나왔|나와|썼|쓴|냈|낸|청구된|청구됐|남았|남은|사용량|사용한|사용했");

	/** 본인에게 남은 잔여량을 가리키는 표현("남은 데이터가 얼마야?"). */
	private static final Pattern REMAINING = Pattern.compile(
			"(?:남은|잔여) ?(?:데이터|통화|문자|포인트|멤버십|혜택|요금|기간)");

	/** 잔여량을 묻는 말. */
	private static final Pattern REMAINING_ASK = Pattern.compile("얼마|몇|궁금|확인|알려|조회");

	/**
	 * 지금 가입·이용 중인 상태를 가리키는 표현("지금 이용 중인 요금제", "현재 가입된 부가서비스"). 1인칭이 없어도
	 * 본인 계정의 현재 상태를 묻는 문장이다.
	 */
	private static final Pattern CURRENT_HOLDING = Pattern.compile(
			"(?:지금|현재) ?(?:가입|이용|사용) ?(?:중인|된|한)|(?:지금|현재) ?(?:쓰고 ?있는|쓰는|쓰던)");

	/** {@link #CURRENT_HOLDING}이 가리키는 대상. */
	private static final Pattern HOLDING_NOUN = Pattern.compile("요금제|부가 ?서비스|서비스|회선|번호|약정|로밍|멤버십|플랜|상품");

	/** 현재 상태를 묻는 말. */
	private static final Pattern HOLDING_ASK = Pattern.compile("뭐|어떤|무엇|몇|알려|궁금|확인|조회|목록");

	/**
	 * 본인 가구·회사를 가리키는 소유 표현. "우리"는 "우리 동네"처럼 지역을 뜻해서 1인칭에서 제외했으므로,
	 * 가족·집처럼 소유 대상이 붙었고 이미 쓴 양을 묻는 문장에서만 본인 기록으로 본다.
	 */
	private static final Pattern OWNER = Pattern.compile("우리 ?(?:집|가족|식구|회사|아이|애들|부모님|엄마|아빠)");

	/** 제도 자체를 묻는 표현. 이월·소멸·적용 같은 말이 있으면 본인 기록이 아니라 정책을 묻는 질문이다. */
	private static final Pattern POLICY_TERM = Pattern.compile("이월|소멸|정산|반영|부과|면제|환불|차감|포함(?:되|돼)");

	/**
	 * 납부 기한·결제일처럼 제도의 일정이나 다음 달 이후의 일을 묻는 표현. "이번 달 요금이 청구됐는데 납부 기한이 언제까지예요?"는
	 * 본인 금액을 묻는 것이 아니라 납부 일정 안내(FAQ)를 묻는 질문이다. 시점+청구 표현으로 본인 기록을 판별할 때만 제외 사유로
	 * 쓴다("내 결제일이 언제야?"는 1인칭 판별이 그대로 잡는다).
	 */
	private static final Pattern SCHEDULE_OR_FUTURE = Pattern.compile(
			"기한|납기|납부일|마감|청구일|결제일|언제 ?까지|다음 ?달|다음 ?분기|내년");

	/**
	 * 할 수 있는지(가능 여부)를 묻거나 요금제·서비스를 바꾸려는 의도가 드러난 표현. 1인칭이 없는 문장에서 본인 기록으로 판별할 때
	 * 오탐을 막으려고 쓴다("지금 쓰는 요금제를 20기가짜리로 바꾸고 싶은데 뭐가 있어요?", "남은 건수를 다음 달에 쓸 수 있나요?").
	 */
	private static final Pattern ACTION_OR_CAPABILITY = Pattern.compile(
			"수 ?있|바꾸|바꿔|변경|추가하|가입하|해지|신청|옮기|추천");

	/**
	 * 방법·절차·위치를 묻는 행동 질문. 이런 질문은 FAQ가 답할 수 있어 개인 정보 조회로 보지 않는다.
	 * "확인하는 곳이 어디예요?"처럼 어디서 확인하는지 묻는 질문도 FAQ("…어디서 확인하나요?")가 답한다.
	 */
	private static final Pattern HOW_TO = Pattern.compile(
			"방법|어떻게 (?:하|해|신청|변경|바꿔|신고|처리|취소|등록|받|발급|확인|조회|열람|얻|이용|사용)"
					+ "|신고|절차|하려면|하고 ?싶|할 ?수 ?있|가능|어디");

	/**
	 * 요금이 언제 빠지는지, 언제 적용되는지처럼 제도의 시점을 묻는 표현. "내 통신요금에서 언제 빠져?"는 계정의 실제 값을
	 * 묻는 것이 아니라 정책을 묻는 질문이라 FAQ가 답한다. 실제 값을 묻는 "계약 만료일이 언제야?"와 구분한다.
	 */
	private static final Pattern POLICY_TIMING = Pattern.compile(
			"언제 ?(?:까지|부터)? ?(?:빠|적용|청구|환불|삭제|해지|반영|처리|발송|들어|바뀌|끝나|풀리|차단|정지|취소|결제)");

	/** 구체적인 타사 이름. 영문은 다른 단어의 일부(예: KTX)와 헷갈리지 않도록 앞뒤 알파벳을 제외한다. */
	private static final Pattern BRAND = Pattern.compile(
			"(?<![A-Za-z])(?:SKT|KT|SK)(?![A-Za-z])|SK텔레콤|에스케이|케이티|(?<![A-Za-z])U\\+|LG ?U\\+|LG유플러스|엘지유플러스"
					+ "|알뜰폰|통신 ?3사|타사|경쟁사|통신사 ?(?:끼리|별|마다)");

	/**
	 * 일반적인 타사 표현. "다른 통신사에서 옮겨오려면…"(번호이동)은 정상 질문이라, 비교·혜택 같은 표현이 함께
	 * 있을 때만 잡는다. "통신사 비교"는 그 자체로 비교 질문이다.
	 */
	private static final Pattern GENERIC_COMPETITOR = Pattern.compile(
			"(?:타 ?통신사|다른 ?통신사|(?:기존|원래|예전|지금|현재)(?:에|엔)? ?(?:쓰고 ?있는|쓰던|쓰는|사용하던|사용하는|이용하던|이용하는) ?통신사)"
					+ "(?=.*(?:비교|혜택|프로모션|싸다|싸지|더 ?싸|저렴|비싸|이득|나은|장점|차이))|통신사 ?비교");

	/**
	 * 타사 이름이 나와도 VITA의 번호이동·호환·이용 가능 여부를 묻는 표현("SKT에서 번호이동 하려면?", "알뜰폰 가입자도
	 * 영상통화 되나요?"). 이런 질문은 FAQ가 답한다.
	 */
	private static final Pattern MIGRATION_OR_COMPATIBILITY = Pattern.compile(
			"번호 ?이동|옮겨|옮기|옮길|넘어오|넘어와|가능|이용할 ?수|사용할 ?수|쓸 ?수|보낼 ?수|되나요|돼요");

	/** 타사의 요금·혜택을 묻거나 VITA와 견주는 표현. 이 표현이 함께 있으면 호환 질문으로 보지 않고 타사 질문으로 막는다. */
	private static final Pattern COMPARE_OR_PRICE = Pattern.compile(
			"요금|가격|추천|비교|혜택|프로모션|얼마|할인|이벤트|싸|저렴|비싸|약정|위약금|결합|이득|나은|낫|차이|어때|좋아|좋은|대비");

	/** VITA 이용 품질을 타사와 견주어 불편을 말하는 문장("타사 보다 느린 것 같아요"). FAQ(속도·품질)가 답한다. */
	private static final Pattern QUALITY_COMPLAINT = Pattern.compile(
			"(?:타사|다른 ?통신사) ?보다.{0,10}(?:느리|느린|느려|느립|끊기|끊겨|불안정)");

	private IrrelevantQueryDetector() {
	}

	/**
	 * 질문에 해당하는 규칙을 모두 찾는다.
	 *
	 * @return 해당하는 규칙 집합. 하나도 없으면 빈 집합
	 */
	public static Set<Rule> detect(String query) {
		Set<Rule> rules = EnumSet.noneOf(Rule.class);
		if (query == null || query.isBlank()) {
			return rules;
		}

		if (isPersonalLookup(query)) {
			rules.add(Rule.PERSONAL_LOOKUP);
		}
		if (BRAND.matcher(query).find() && !isMigrationOrComplaint(query)) {
			rules.add(Rule.COMPETITOR_BRAND);
		}
		if (GENERIC_COMPETITOR.matcher(query).find()) {
			rules.add(Rule.COMPETITOR_GENERIC);
		}
		return rules;
	}

	/**
	 * 본인 계정의 실제 값·상태·기록을 묻는 질문인지 본다. 방법·절차를 묻거나 제도의 시점·정책을 묻는 질문은 FAQ가 답하므로 제외한다.
	 * 다음 중 하나에 해당하면 본인 정보 조회로 본다.
	 * <ol>
	 *   <li>1인칭 + 조회 표현("제 남은 데이터 알려주세요"). 주어형 1인칭("나는", "저는")은 자기소개로도 쓰여서 {@link #asksOwnRecord}로 한 번 더 가른다</li>
	 *   <li>시점 + 사용·청구 대상 + 이미 쓴 양·청구된 금액("지난달 요금이 얼마 나왔는지")</li>
	 *   <li>남은 잔여량 + 묻는 말("남은 데이터가 얼마야?")</li>
	 *   <li>지금 가입·이용 중인 대상 + 묻는 말("지금 이용 중인 요금제가 뭐예요?")</li>
	 *   <li>우리 집·가족 같은 소유 표현 + 사용·청구 대상 + 이미 쓴 양·청구된 금액</li>
	 * </ol>
	 * 1인칭이 없는 문장(2~5번)은 오탐 위험이 커서, 가능 여부를 묻거나 바꾸려는 의도가 보이면 제외한다. "제 납부 내역을 조회할 수
	 * 있을까요?"처럼 1인칭이 있어도 기록을 볼 수 있는지 묻는 문장은 FAQ("…내 인증 내역을 볼 수 있나요?")와 모양이 같아서
	 * 구분할 수 없으므로 잡지 않는다.
	 */
	private static boolean isPersonalLookup(String query) {
		if (POLICY_TIMING.matcher(query).find() || POLICY_TERM.matcher(query).find() || HOW_TO.matcher(query).find()) {
			return false;
		}
		if (FIRST_PERSON.matcher(query).find() && LOOKUP.matcher(query).find() && asksOwnRecord(query)) {
			return true;
		}
		if (ACTION_OR_CAPABILITY.matcher(query).find()) {
			return false;
		}
		boolean usage = USAGE_TOPIC.matcher(query).find();
		if (usage && CONSUMED.matcher(query).find() && !SCHEDULE_OR_FUTURE.matcher(query).find()
				&& (PERIOD.matcher(query).find() || OWNER.matcher(query).find())) {
			return true;
		}
		if (REMAINING.matcher(query).find() && REMAINING_ASK.matcher(query).find()) {
			return true;
		}
		return CURRENT_HOLDING.matcher(query).find() && HOLDING_NOUN.matcher(query).find()
				&& HOLDING_ASK.matcher(query).find();
	}

	/**
	 * 1인칭과 조회 표현이 함께 있을 때, 실제로 본인 계정의 기록·상태를 묻는지 가른다.
	 * <ul>
	 *   <li>소유형("내 요금 알려줘", "제 남은 데이터")은 조회 표현만 있어도 본인 기록 조회다.</li>
	 *   <li>주어형("나는/저는/내가/제가")은 "나는 대학생이야. 요금제 알려줘"처럼 자기소개 뒤의 요청일 수 있다. 가입하거나 바꾸려는
	 *       의도(추천, 가입하면, 바꾸고 등)가 보이면 조회가 아니고, 값·상태·내역을 묻는 말({@link #RECORD_EVIDENCE})이나 본인이 쓰는
	 *       대상({@link #OWN_HOLDING})이 있거나, 일반 요청어에 이미 쓴 양·시점·현재 이용 중이라는 표현이 함께 있어야 조회로 본다.</li>
	 * </ul>
	 */
	private static boolean asksOwnRecord(String query) {
		if (POSSESSIVE.matcher(query).find()) {
			return true;
		}
		if (ACTION_OR_CAPABILITY.matcher(query).find()) {
			return false;
		}
		if (RECORD_EVIDENCE.matcher(query).find() || OWN_HOLDING.matcher(query).find()) {
			return true;
		}
		// "얼마", "몇", "궁금", "알려줘" 같은 말은 이미 쓴 양·시점·현재 이용 중이라는 표현과 함께 있을 때만 본인 기록을 묻는 것으로 본다.
		return (GENERIC_REQUEST.matcher(query).find() || VALUE_ASK.matcher(query).find())
			&& (CONSUMED.matcher(query).find() || PERIOD.matcher(query).find() || CURRENT_HOLDING.matcher(query).find());
	}

	/**
	 * 타사 이름이 있어도 번호이동·호환 가능 여부를 묻거나(요금·혜택 비교 표현이 없을 때) 타사보다 느리다는 불편을 말하는
	 * 질문인지 본다. 이런 질문은 타사 정보를 묻는 것이 아니라 VITA 이용에 관한 것이다.
	 */
	private static boolean isMigrationOrComplaint(String query) {
		boolean migrationOrCompatibility = MIGRATION_OR_COMPATIBILITY.matcher(query).find()
				&& !COMPARE_OR_PRICE.matcher(query).find();
		return migrationOrCompatibility || QUALITY_COMPLAINT.matcher(query).find();
	}
}
