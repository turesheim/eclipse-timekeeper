package net.resheim.eclipse.timekeeper.domain;

import java.util.Objects;
import java.util.Set;

final class Values {
	private Values() { }

	static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new DomainValidationException(field, field + " must not be blank");
		}
		return value.trim();
	}

	static long version(long value) {
		if (value < 0) throw new DomainValidationException("version", "version must not be negative");
		return value;
	}

	static <T> T required(T value, String field) {
		if (value == null) throw new DomainValidationException(field, field + " must not be null");
		return Objects.requireNonNull(value);
	}

	static <T> Set<T> set(Set<T> values, String field) {
		if (values == null) return Set.of();
		if (values.stream().anyMatch(Objects::isNull)) {
			throw new DomainValidationException(field, field + " must not contain null values");
		}
		return Set.copyOf(values);
	}
}
