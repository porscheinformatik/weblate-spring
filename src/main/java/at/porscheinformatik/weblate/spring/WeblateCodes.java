package at.porscheinformatik.weblate.spring;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeblateCodes {
  private static final ConcurrentHashMap<Locale, String> weblateCodes = new ConcurrentHashMap<>();

  private static final Pattern WEBLATE_LOCALE_PATTERN = Pattern.compile(
    "^(?<lang>[a-z]{2,3})(?:_(?<script>[a-z]{4}))?(?:_(?<region>[a-z]{2}))?(?:_(?<variant>[a-z0-9-]{5,8})|@(?<xvariant>[a-z0-9-]{1,8}))?$", Pattern.CASE_INSENSITIVE);

  /**
   * resolves a WeblateCode for a given Locale
   *
   * @param locale
   * @return WeblateCode or null if locale is not registered
   */
  public static String getCodeForLocale(Locale locale) {
    return weblateCodes.getOrDefault(locale, null);
  }

  /**
   * registers a Locale - WeblateCode pair.
   *
   * @param locale a unique non-null
   * @param code   an WeblateCode
   */
  public static void registerLocale(Locale locale, String code) {
    Objects.requireNonNull(locale);

    if (Objects.isNull(code)) {
      weblateCodes.remove(locale);
    } else {
      weblateCodes.put(locale, code);
    }
  }

  /**
   * attempts to derive and register a java.util.Locale from a given code.
   *
   * @return true when code is registered or could be registered, false when no locale could be derived or derived locale was already registered for another code
   */
  public static boolean deriveLocaleFromCode(String code) {
    if (weblateCodes.containsValue(code)) {
      return true;
    }

    final Matcher codeMatcher = WEBLATE_LOCALE_PATTERN.matcher(code);
    if (codeMatcher.matches()) {
      final Locale.Builder builder = new Locale.Builder();

      builder.setLanguage(codeMatcher.group("lang"));

      Optional.ofNullable(codeMatcher.group("script"))
        .ifPresent(builder::setScript);

      Optional.ofNullable(codeMatcher.group("region"))
        .ifPresent(builder::setRegion);

      Optional.ofNullable(codeMatcher.group("variant"))
        .ifPresent(builder::setVariant);

      // x-lvariant is special as normally variant must be 6-8 characters long, whereas lvariant can be shorter
      Optional.ofNullable(codeMatcher.group("xvariant"))
        .ifPresent(xvariant -> builder.setExtension('x', "lvariant-" + xvariant));

      final Locale locale = builder.build();
      if (!weblateCodes.containsKey(locale)) {
        registerLocale(locale, code);
        return true;
      }
    }

    return false;
  }
}
