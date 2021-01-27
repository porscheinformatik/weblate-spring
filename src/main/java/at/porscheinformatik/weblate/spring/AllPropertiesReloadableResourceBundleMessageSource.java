package at.porscheinformatik.weblate.spring;

import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;
import java.util.Properties;

/**
 * {@link ReloadableResourceBundleMessageSource} implementing {@link AllPropertiesSource}.
 */
public class AllPropertiesReloadableResourceBundleMessageSource extends ReloadableResourceBundleMessageSource
  implements AllPropertiesSource {

  @Override
  public Properties getAllProperties(Locale locale) {
    return super.getMergedProperties(locale).getProperties();
  }
}