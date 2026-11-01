package dev.arche.communication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.collection.ContactQuotaGuard;
import dev.arche.common.BusinessException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 템플릿 치환과 연락 타임라인 병합 (R59, guide 13.2) */
class CommunicationRulesTest {

	@Test
	void 템플릿_치환자를_계약_값으로_채운다() {
		var values = TemplateRenderer.values("홍길동", "L00001001", 18, "1,135,616원", "9,000,000원", null, "2026-06-12");
		assertThat(TemplateRenderer.render("{고객명}님, {계약번호} 연체 {연체일수}일, 완제 {완제금액}, 납입일 {납입일}", values))
				.isEqualTo("홍길동님, L00001001 연체 18일, 완제 1,135,616원, 납입일 -");
	}

	@Test
	void 모르는_치환자는_저장할_때_거절한다() {
		TemplateRenderer.validate("{고객명} {기준일}");
		assertThatThrownBy(() -> TemplateRenderer.validate("{고객이름}님")).isInstanceOf(BusinessException.class)
				.hasMessageContaining("{고객이름}");
	}

	@Test
	void 발송_수단은_7일_7회에_세는_연락_채널로_바뀐다() {
		assertThat(CommunicationService.quotaChannel(DispatchChannel.SMS)).isEqualTo(ContactQuotaGuard.Channel.SMS);
		assertThat(CommunicationService.quotaChannel(DispatchChannel.FAX)).isEqualTo(ContactQuotaGuard.Channel.FAX);
		assertThat(CommunicationService.quotaChannel(DispatchChannel.POST)).isEqualTo(ContactQuotaGuard.Channel.POST);
		// 팩스·우편 추심 안내도 한도에 1회로 센다
		var guard = new ContactQuotaGuard();
		LocalDate d = LocalDate.of(2026, 6, 1);
		for (int i = 0; i < 6; i++) {
			guard.attempt(new ContactQuotaGuard.Attempt(d, ContactQuotaGuard.Channel.CALL));
		}
		assertThat(guard.attempt(new ContactQuotaGuard.Attempt(d, ContactQuotaGuard.Channel.POST)).allowed()).isTrue();
		assertThat(guard.attempt(new ContactQuotaGuard.Attempt(d, ContactQuotaGuard.Channel.FAX)).allowed()).isFalse();
	}

	@Test
	void 타임라인은_출처를_합쳐_최근_순으로_나누고_중복된_연락_기록은_뺀다() {
		OffsetDateTime t = OffsetDateTime.parse("2026-06-12T10:00:00+09:00");
		LocalDate d = t.toLocalDate();
		var contacts = List.of(item(Timeline.Kind.CONTACT, 1, t.minusHours(3)), item(Timeline.Kind.CONTACT, 2, t));
		var autos = List.of(item(Timeline.Kind.AUTO_MESSAGE, 7, t.minusDays(1)));
		var dispatches = List.of(item(Timeline.Kind.DISPATCH, 5, t));
		var memos = List.of(item(Timeline.Kind.MEMO, 3, t.minusHours(1)));

		var page0 = Timeline.merge(contacts, autos, dispatches, memos, Set.of(2L), 0, 2);
		assertThat(page0.totalElements()).isEqualTo(4);
		assertThat(page0.hasNext()).isTrue();
		assertThat(page0.content()).extracting(Timeline.Item::kind).containsExactly(Timeline.Kind.DISPATCH,
				Timeline.Kind.MEMO);
		var page1 = Timeline.merge(contacts, autos, dispatches, memos, Set.of(2L), 1, 2);
		assertThat(page1.content()).extracting(Timeline.Item::sourceId).containsExactly(1L, 7L);
		assertThat(page1.hasNext()).isFalse();
		assertThat(Timeline.merge(contacts, autos, dispatches, memos, Set.of(), 5, 2).content()).isEmpty();
		assertThat(d).isEqualTo(LocalDate.of(2026, 6, 12));
	}

	private static Timeline.Item item(Timeline.Kind kind, long id, OffsetDateTime at) {
		return new Timeline.Item(kind, id, at, at.toLocalDate(), "SMS", null, "내용", "SENT", null, "tester");
	}
}
