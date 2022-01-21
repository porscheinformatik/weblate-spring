package at.porscheinformatik.weblate.spring;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for handling Weblate locale codes.
 */
public class WeblateUtils {

  private static final Pattern WEBLATE_LOCALE_PATTERN = Pattern.compile(
    "^(?<lang>[a-z]{2,3})(?:_(?<script>[a-z]{4}))?(?:_(?<region>[a-z]{2}))?(?:_(?<variant>[a-z0-9-]{5,8})|@(?<xvariant>[a-z0-9-]{1,8}))?$", Pattern.CASE_INSENSITIVE);

  /**
   * Attempts to derive a {@link Locale} from a given code.
   *
   * @param code the code to derive from
   * @return the derived locale or null when no locale could be derived
   */
  public static Locale deriveLocaleFromCode(String code) {
    if (Objects.isNull(code)) {
      return null;
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

      return builder.build();
    }

    return null;
  }
}
