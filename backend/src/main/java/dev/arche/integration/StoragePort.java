package dev.arche.integration;

/**
 * 파일 저장 포트 (guide 2.4 StoredFile). 업무 모듈은 파일 본문 대신 저장 키·크기·해시(메타데이터)만 보관한다.
 * 기본 어댑터는 로컬 디스크(설정 디렉터리)이며, 오브젝트 스토리지·NAS는 같은 포트의 다른 어댑터로 교체한다.
 */
public interface StoragePort {

	/** 저장 결과. key는 load에 그대로 넘기는 불투명한 값 */
	record StoredObject(String key, long size, String sha256) {
	}

	/**
	 * @param category 저장 구분 (영문 소문자·숫자·하이픈, 예: "application-documents")
	 */
	StoredObject store(String category, byte[] content);

	byte[] load(String key);
}
