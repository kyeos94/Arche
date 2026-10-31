package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 채무조정 (G23, R9·R23): 견적 → 결재권 → 미납이면 체결 거절 → 체결일 입금 후 체결.
 */
class WorkoutIT extends IntegrationTest {

	@Test
	void 채무조정_체결_흐름() throws Exception {
		businessDate("2026-09-01");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long a = openLoan(head, customer(head, "조정"), "P3", 12_000_000, "15.0", 12, 0, 25, "2026-03-20",
				staffId(collectorId));
		pay(teller, a, "2026-04-25", 1_177_534, tx());
		pay(teller, a, "2026-05-25", 1_135_616, tx());
		JsonNode before = detail(head, a);
		long late = before.get("outstandingLateInterest").asLong();
		long normal = before.get("outstandingNormalInterest").asLong();
		assertThat(late).isPositive();

		String quoteUrl = "/api/loans/" + a + "/workout-quote?waiveLate=" + late + "&installments=24&dueDay=25";
		JsonNode quote = ok(getJson(collector, quoteUrl));
		assertThat(quote.get("waiveLate").asLong()).isEqualTo(late);
		assertThat(quote.get("requiredPayment").asLong()).isEqualTo(normal);
		assertThat(quote.get("schedulePreview")).hasSize(24);
		assertThat(quote.get("installmentAmount").asLong()).isEqualTo(484_866);

		var body = Map.of("waiveLate", late, "waiveNormal", 0, "waivePrincipal", 0, "installments", 24, "dueDay", 25,
				"reason", "생계 곤란");
		// 채권담당은 정상이자 감면 결재권이 없다
		assertThat(postJson(collector, "/api/loans/" + a + "/workouts",
				Map.of("waiveLate", late, "waiveNormal", 1_000, "waivePrincipal", 0, "installments", 24, "dueDay", 25,
						"reason", "x"))
				.andReturn().getResponse().getStatus()).isEqualTo(403);
		// 미감면 정상이자를 아직 받지 않았으면 체결하지 않는다 (R23)
		var unpaid = postJson(collector, "/api/loans/" + a + "/workouts", body).andReturn().getResponse();
		assertThat(unpaid.getStatus()).isEqualTo(422);
		assertThat(unpaid.getContentAsString()).contains("WORKOUT_UNPAID");
		assertThat(detail(head, a).get("status").asString()).isNotEqualTo("WORKOUT");

		pay(teller, a, "2026-09-01", normal, tx());
		ok(postJson(collector, "/api/loans/" + a + "/workouts", body));

		JsonNode after = detail(head, a);
		assertThat(after.get("status").asString()).isEqualTo("WORKOUT");
		assertThat(after.get("principalBalance").asLong()).isEqualTo(10_000_000);
		assertThat(after.get("installmentAmount").asLong()).isEqualTo(484_866);
		assertThat(after.get("outstandingLateInterest").asLong()).isZero();
		assertThat(after.get("nextDueDate").asString()).isEqualTo("2026-10-25");
		JsonNode schedule = ok(getJson(head, "/api/loans/" + a + "/schedule"));
		assertThat(schedule.get("current")).hasSize(24);
		assertThat(schedule.get("current").get(0).get("principalDue").asLong()).isEqualTo(262_949);
		assertThat(schedule.get("current").get(0).get("plannedInterest").asLong()).isEqualTo(221_917);

		boolean waiver = false;
		for (JsonNode e : ok(getJson(head, "/api/loans/" + a + "/ledger"))) {
			waiver |= e.get("type").asString().equals("WAIVER") && e.get("lateInterest").asLong() == late;
		}
		assertThat(waiver).isTrue();
	}
}
