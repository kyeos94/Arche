package dev.arche.common.crypto;

import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
class CryptoConfig {

	@Bean
	FieldCipher fieldCipher(CryptoProperties props) {
		FieldCipher cipher = new FieldCipher(Base64.getDecoder().decode(props.fieldKeyV1()));
		EncryptedStringConverter.register(cipher);
		return cipher;
	}

	@Bean
	BlindIndexer blindIndexer(CryptoProperties props) {
		return new BlindIndexer(Base64.getDecoder().decode(props.indexKey()));
	}
}
