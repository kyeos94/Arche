package dev.arche.integration.internal.fixedlength;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 고정길이 전문 레코드 선언 (guide 11.2). record 타입에 붙이고, 각 구성 요소에 {@link Field}로 위치를 적는다.
 * 길이는 문자 수가 아니라 바이트 수다 (한글은 MS949에서 2바이트).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FixedLengthRecord {

	/** 레코드 전체 바이트 길이. 필드가 채우지 않는 구간은 공백(filler) */
	int length();

	/**
	 * 고정길이 필드. offset은 0부터의 바이트 위치. 문자열은 왼쪽 정렬 + 공백, 숫자는 오른쪽 정렬 + '0', 날짜는 yyyyMMdd.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.RECORD_COMPONENT, ElementType.FIELD })
	@interface Field {

		int offset();

		int length();
	}
}
