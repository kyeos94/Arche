package dev.arche.integration.internal;

import dev.arche.common.BusinessException;
import dev.arche.integration.StoragePort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로컬 디스크 저장 어댑터. 저장 위치는 {@code arche.storage.dir}. 파일 이름은 원본 이름이 아니라 무작위 UUID로 만들어
 * 경로 조작과 개인정보(파일명)가 디스크에 남는 것을 막는다. 클라우드 SDK는 쓰지 않는다 (ADR 0007).
 */
@Component
class LocalDiskStorageAdapter implements StoragePort {

	private final Path root;

	LocalDiskStorageAdapter(@Value("${arche.storage.dir}") String dir) {
		this.root = Path.of(dir).toAbsolutePath().normalize();
	}

	@Override
	public StoredObject store(String category, byte[] content) {
		if (category == null || !category.matches("[a-z0-9-]{1,40}")) {
			throw new IllegalArgumentException("저장 구분이 올바르지 않습니다: " + category);
		}
		String key = category + "/" + UUID.randomUUID();
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, content, StandardOpenOption.CREATE_NEW);
		} catch (IOException e) {
			throw new UncheckedIOException("파일 저장 실패", e);
		}
		return new StoredObject(key, content.length, sha256(content));
	}

	@Override
	public byte[] load(String key) {
		Path p = resolve(key);
		if (!Files.isRegularFile(p)) {
			throw new BusinessException("FILE_NOT_FOUND", "파일이 없습니다");
		}
		try {
			return Files.readAllBytes(p);
		} catch (IOException e) {
			throw new UncheckedIOException("파일 읽기 실패", e);
		}
	}

	Path resolve(String key) {
		if (key == null || !key.matches("[a-z0-9-]{1,40}/[0-9a-f-]{36}")) {
			throw new IllegalArgumentException("저장 키가 올바르지 않습니다");
		}
		Path p = root.resolve(key).normalize();
		if (!p.startsWith(root)) {
			throw new IllegalArgumentException("저장 키가 올바르지 않습니다");
		}
		return p;
	}

	static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
