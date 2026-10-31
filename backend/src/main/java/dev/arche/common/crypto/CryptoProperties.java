package dev.arche.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 암호화 키 (Base64, 32바이트). 운영에서는 KMS·Vault에서 받은 데이터 키를 환경변수로 주입한다 (guide 10.2).
 *
 * @param fieldKeyV1 컬럼 암호화 키 (AES-256-GCM)
 * @param indexKey   블라인드 인덱스 키 (HMAC-SHA256), 암호화 키와 분리
 */
@ConfigurationProperties("arche.crypto")
public record CryptoProperties(String fieldKeyV1, String indexKey) {
}
