package at.porscheinformatik.weblate.spring;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class WeblateCodesTest {

  @ParameterizedTest
  @MethodSource("validCodesProvider")
  void validCodesToLocale(String code, Locale locale) {
    assertEquals(locale, WeblateUtils.deriveLocaleFromCode(code));
  }

  static Stream<Arguments> validCodesProvider() {
    return Stream.of(
      arguments(null, null),
      arguments("de_hansel_AT", null), // scripts must be exact 4 char
      arguments("de_AT@thisIsToLong", null),  // variants must be max 8 char
      arguments("de_AT_test", null), // regular variants must be min 5 char
      arguments("de", Locale.GERMAN),
      arguments("en_GB", Locale.forLanguageTag("en-GB")),
      arguments("cz_hanz_ad", Locale.forLanguageTag("cz-hanz-ad")),
      arguments("en_devel", Locale.forLanguageTag("en-devel")),
      arguments("en_GB@test", Locale.forLanguageTag("en-gb-x-lvariant-test")),
      arguments("en_Cyri_UK@test", Locale.forLanguageTag("en-Cyri-UK-x-lvariant-test")),
      arguments("en_Cyri_UK@A", Locale.forLanguageTag("en-Cyri-UK-x-lvariant-A"))
    );
  }

}
