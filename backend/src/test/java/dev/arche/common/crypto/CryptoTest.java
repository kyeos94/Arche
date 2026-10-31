package dev.arche.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.Masking;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CryptoTest {

	private static byte[] key(int fill) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) fill);
		return k;
	}

	@Test
	void 같은_평문도_매번_다른_암호문이고_복호화된다() {
		FieldCipher cipher = new FieldCipher(key(1));
		String a = cipher.encrypt("900101-1234567");
		String b = cipher.encrypt("900101-1234567");
		assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("1234567");
		assertThat(cipher.decrypt(a)).isEqualTo("900101-1234567");
	}

	@Test
	void 다른_키로는_복호화할_수_없다() {
		String stored = new FieldCipher(key(1)).encrypt("secret");
		assertThatThrownBy(() -> new FieldCipher(key(2)).decrypt(stored)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 블라인드_인덱스는_결정적이고_구분기호를_무시한다() {
		BlindIndexer indexer = new BlindIndexer(key(3));
		assertThat(indexer.index("900101-1234567")).hasSize(64).isEqualTo(indexer.index("9001011234567"));
		assertThat(indexer.index("900101-1234567")).isNotEqualTo(new BlindIndexer(key(4)).index("900101-1234567"));
	}

	@Test
	void 마스킹() {
		assertThat(Masking.residentNo("900101-1234567")).isEqualTo("900101-1******");
		assertThat(Masking.phone("010-1234-5678")).isEqualTo("010-****-5678");
	}
}
