package at.porscheinformatik.weblate.spring;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public class WeblateCodesTest {

  @Test
  public void validCodesToLocale() {
    testCodeTolocale("de", Locale.GERMAN);
    testCodeTolocale("en_GB", Locale.forLanguageTag("en-GB"));
    testCodeTolocale("cz_hanz_ad", Locale.forLanguageTag("cz-hanz-ad"));
    testCodeTolocale("en_devel", Locale.forLanguageTag("en-devel"));
    testCodeTolocale("en_GB@test", Locale.forLanguageTag("en-gb-x-lvariant-test"));
    testCodeTolocale("en_Cyri_UK@test", Locale.forLanguageTag("en-Cyri-UK-x-lvariant-test"));
    testCodeTolocale("en_Cyri_UK@A", Locale.forLanguageTag("en-Cyri-UK-x-lvariant-A"));
  }

  @Test
  public void noCodeFound() {
    assertNull(WeblateCodes.getCodeForLocale(Locale.CANADA));
  }

  @Test
  public void registerCodes() {
    // codes "myalias" and "foo_baz" should be only available after they got registered
    assertFalse(WeblateCodes.deriveLocaleFromCode("myalias"));
    assertFalse(WeblateCodes.deriveLocaleFromCode("foo_baz"));

    WeblateCodes.registerLocale(Locale.forLanguageTag("de-DE-myalias"), "myalias");
    WeblateCodes.registerLocale(Locale.forLanguageTag("fo-BA-foobar"), "foo_baz");

    testCodeTolocale("myalias", Locale.forLanguageTag("de-DE-myalias"));
    testCodeTolocale("foo_baz", Locale.forLanguageTag("fo-BA-foobar"));
  }

  private void testCodeTolocale(String code, Locale locale) {
    assertTrue(WeblateCodes.deriveLocaleFromCode(code));
    assertEquals(code, WeblateCodes.getCodeForLocale(locale));
  }

}
