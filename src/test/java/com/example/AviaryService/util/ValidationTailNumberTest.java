package com.example.AviaryService.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationTailNumberTest {

    @Test
    void keyIgnoresCaseAndSpaces() {
        assertThat(Validation.tailNumberKey("n 731 kr")).isEqualTo("N731KR");
        assertThat(Validation.tailNumberKey(" N731KR ")).isEqualTo("N731KR");
    }

    @Test
    void blankKeyIsNull() {
        assertThat(Validation.tailNumberKey(null)).isNull();
        assertThat(Validation.tailNumberKey("   ")).isNull();
    }

    @Test
    void normalizeTrimsAndUpperCases() {
        assertThat(Validation.normalizeTailNumber("  n731kr ")).isEqualTo("N731KR");
        assertThat(Validation.normalizeTailNumber("n 731 kr")).isEqualTo("N731KR");
        assertThat(Validation.normalizeTailNumber("g-abcd")).isEqualTo("G-ABCD");
        assertThat(Validation.normalizeTailNumber(null)).isNull();
    }

    @Test
    void rejectsSymbols() {
        assertThatThrownBy(() -> Validation.requireValidTailNumber("N731KR!", true))
            .hasMessage("Use letters, numbers, spaces, or dashes.");
    }

    @Test
    void rejectsOverLength() {
        assertThatThrownBy(() -> Validation.requireValidTailNumber("N".repeat(Validation.MAX_TEXT + 1), false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredBlankRejected() {
        assertThatThrownBy(() -> Validation.requireValidTailNumber("  ", true))
            .hasMessage("Enter a tail number.");
    }

    @Test
    void optionalBlankAccepted() {
        assertThatCode(() -> Validation.requireValidTailNumber("", false)).doesNotThrowAnyException();
        assertThatCode(() -> Validation.requireValidTailNumber("G-ABCD", true)).doesNotThrowAnyException();
    }
}
