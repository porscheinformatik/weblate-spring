package at.porscheinformatik.weblate.spring;

import java.util.Locale;
import java.util.Properties;

/**
 * Interface for getting all translations out of a {@link org.springframework.context.MessageSource} as {@link Properties}.
 */
public interface AllPropertiesSource {

  /**
   * @param locale the locale to load the properties
   * @return all properties provided for this locale
   */
  Properties getAllProperties(Locale locale);
}