package dev.arche.integration.internal;

import dev.arche.common.Masking;
import dev.arche.integration.FaxPort;
import dev.arche.integration.PostalPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Mock 어댑터가 만든 파일을 두는 로컬 폴더 */
@Component
class MockFileStore {

	private final Path root;

	MockFileStore(@Value("${arche.integration.files-dir:build/integration-files}") String root) {
		this.root = Path.of(root);
	}

	Path write(String folder, String name, byte[] content) {
		try {
			Path dir = root.resolve(folder);
			Files.createDirectories(dir);
			Path file = dir.resolve(name);
			Files.write(file, content);
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	boolean exists(String folder, String name) {
		return Files.exists(root.resolve(folder).resolve(name));
	}

	byte[] read(String folder, String name) {
		try {
			return Files.readAllBytes(root.resolve(folder).resolve(name));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

/**
 * Mock 팩스: 실제로 보내지 않고 발송 문서를 로컬 파일로 저장한다. 수신 번호 끝 4자리가 0000이면 "응답 없음"으로 실패시킨다
 * (실패 처리 화면 확인용). 번호는 이력·로그에 마스킹한다.
 */
@Component
class MockFaxAdapter implements FaxPort {

	static final String INSTITUTION = "가상 팩스중계";

	private final MockFileStore files;
	private final CallLogger calls;

	MockFaxAdapter(MockFileStore files, CallLogger calls) {
		this.files = files;
		this.calls = calls;
	}

	@Override
	public FaxResult send(FaxRequest r) {
		return calls.call(INSTITUTION, "팩스 발송", Masking.phone(r.faxNumber()) + " / " + r.title(), () -> {
			String id = "FAX-" + UUID.randomUUID();
			String digits = r.faxNumber().replaceAll("[^0-9]", "");
			if (digits.endsWith("0000")) {
				return new FaxResult(id, false, "NO_ANSWER", null);
			}
			String name = id + ".txt";
			files.write("fax", name, ("[팩스] " + r.title() + "\n수신: " + Masking.phone(r.faxNumber()) + "\n\n"
					+ r.body() + "\n").getBytes(StandardCharsets.UTF_8));
			return new FaxResult(id, true, "OK", "fax/" + name);
		}, res -> new CallLogger.Outcome(res.resultCode(), res.accepted()));
	}
}

/**
 * Mock 등기우편: 발송 문서를 로컬 파일로 저장하고, 배달 상태는 접수 후 경과일과 등기번호로 정해지는 의사 난수로 진행한다
 * (1일째 배송 중, 2~3일째 배달 완료, 등기번호 해시가 10의 배수면 반송). 같은 입력이면 항상 같은 결과다.
 */
@Component
class MockPostalAdapter implements PostalPort {

	static final String INSTITUTION = "가상 우편";

	private final MockFileStore files;
	private final CallLogger calls;

	MockPostalAdapter(MockFileStore files, CallLogger calls) {
		this.files = files;
		this.calls = calls;
	}

	@Override
	public Accepted send(PostalRequest r) {
		return calls.call(INSTITUTION, "등기 접수", "고객 #" + r.customerId() + " / " + r.title(), () -> {
			String trackingNo = "RG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
			String name = trackingNo + ".txt";
			files.write("postal", name, ("[등기우편] " + r.title() + "\n등기번호: " + trackingNo + "\n수신: 고객 #"
					+ r.customerId() + " (주소는 고객 정보에서)\n\n" + r.body() + "\n").getBytes(StandardCharsets.UTF_8));
			return new Accepted(trackingNo, "postal/" + name);
		}, res -> new CallLogger.Outcome("ACCEPTED", true));
	}

	@Override
	public Tracking track(String trackingNo, LocalDate acceptedOn, LocalDate today) {
		return calls.call(INSTITUTION, "배달 조회", trackingNo, () -> progress(trackingNo, acceptedOn, today),
				t -> new CallLogger.Outcome(t.status().name(), true));
	}

	static Tracking progress(String trackingNo, LocalDate acceptedOn, LocalDate today) {
		long days = ChronoUnit.DAYS.between(acceptedOn, today);
		int hash = Math.abs(trackingNo.hashCode());
		if (days < 1) {
			return new Tracking(trackingNo, DeliveryStatus.ACCEPTED, null, "접수");
		}
		int deliveryDays = 2 + hash % 2;
		if (days < deliveryDays) {
			return new Tracking(trackingNo, DeliveryStatus.IN_TRANSIT, null, "배송 중");
		}
		LocalDate on = acceptedOn.plusDays(deliveryDays);
		if (hash % 10 == 0) {
			return new Tracking(trackingNo, DeliveryStatus.RETURNED, on, "수취인 부재로 반송");
		}
		return new Tracking(trackingNo, DeliveryStatus.DELIVERED, on, "배달 완료");
	}
}
