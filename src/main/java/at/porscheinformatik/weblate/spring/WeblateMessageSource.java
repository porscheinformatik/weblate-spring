package at.porscheinformatik.weblate.spring;

import org.springframework.context.MessageSource;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.springframework.web.util.UriUtils.encode;

/**
 * {@link MessageSource} loading texts from Weblate translation server via REST API.
 *
 * <p>
 * If you use the {@link WeblateMessageSource} with a parent {@link org.springframework.context.support.ReloadableResourceBundleMessageSource}
 * and want to resolve all properties you can use the {@link AllPropertiesReloadableResourceBundleMessageSource}
 * instead.
 * </p>
 */
public class WeblateMessageSource extends AbstractMessageSource implements AllPropertiesSource {
  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_STRING_OBJECT = new ParameterizedTypeReference<List<Map<String, Object>>>() {
  };

  private RestTemplate restTemplate;
  private String baseUrl;
  private String project;
  private String component;
  private String query = "state:>=translated";

  private Set<Locale> existingLocales;
  private final Object existingLocalesLock = new Object();
  private final Map<Locale, Properties> translationsCache = new ConcurrentHashMap<>();

  /**
   * @return the Weblate base URL
   */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Set Weblate base URL.
   *
   * @param baseUrl the URL of your Weblate instance without trailing /
   */
  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /**
   * @return project slug in Weblate
   */
  public String getProject() {
    return project;
  }

  /**
   * Set project.
   *
   * @param project project slug in Weblate
   */
  public void setProject(String project) {
    this.project = project;
  }

  /**
   * @return component slug in Weblate
   */
  public String getComponent() {
    return component;
  }

  /**
   * Set component.
   *
   * @param component component slug in Weblate
   */
  public void setComponent(String component) {
    this.component = component;
  }

  /**
   * @return the Weblate query
   */
  public String getQuery() {
    return query;
  }

  /**
   * Set the Weblate query for extracting the texts. Default is "state:&gt;=translated"
   * <p>
   * See also: <a href="https://docs.weblate.org/en/latest/user/search.html">Weblate Search</a>.
   *
   * @param query the Weblate query
   */
  public void setQuery(String query) {
    this.query = query;
  }

  /**
   * Set the {@link RestTemplate} to use for getting data from Weblate REST API.
   * <p>
   * Please configure the given parameter with UTF-8 as the standard message converter:
   * <pre><code>restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));</code></pre>
   *
   * @param restTemplate the {@link RestTemplate}
   */
  public void setRestTemplate(RestTemplate restTemplate) {
    if (this.restTemplate != null) {
      throw new IllegalStateException("Rest template already set (maybe throgh useAuthentcation(.");
    }
    this.restTemplate = restTemplate;
  }

  /**
   * Sets {@link WeblateAuthenticationInterceptor} for calling the REST API. <b>Be aware</b>: this
   * replaces all interceptors in the {@link RestTemplate}.
   *
   * @param authToken Weblate API token
   */
  public void useAuthentication(String authToken) {
    if (restTemplate == null) {
      restTemplate = createRestTemplate();
    }
    restTemplate.setInterceptors(singletonList(new WeblateAuthenticationInterceptor(authToken)));
  }

  /**
   * Clears the cache for all locales and message bundles.
   */
  public void clearCache() {
    logger.info("Going to clear cache...");
    synchronized (existingLocalesLock) {
      existingLocales = null;
    }
    translationsCache.clear();
  }

  /**
   * Reloads the translations for the given locales from Weblate.
   * <p>
   * Only if the translations from Weblate could be loaded, the translation cache will be updated
   *
   * @param locales the locales that should be reloaded
   */
  public void reload(Locale... locales) {
    logger.info("Going to reload the translations ...");

    if (locales != null && locales.length > 0) {
      for (Locale locale : locales) {
        logger.info(String.format("Reload translation for locale %s", locale));
        loadTranslations(locale, true);
      }
    }
  }

  private Properties loadTranslations(Locale locale, boolean forceReload) {
    Properties translations = translationsCache.get(locale);

    if (translations != null && !forceReload) {
      return translations;
    }

    translations = loadTranslation(new Locale(locale.getLanguage()));

    if (StringUtils.hasText(locale.getCountry())) {
      Properties countrySpecific = loadTranslation(locale);
      translations.putAll(countrySpecific);
    }

    translationsCache.put(locale, translations);

    return translations;
  }

  private Properties loadTranslation(Locale language) {
    synchronized (existingLocalesLock) {
      if (existingLocales == null) {
        existingLocales = loadLocales();
      }

      if (!existingLocales.contains(language)) {
        logger.info("Locale not exists " + language);
        return new Properties();
      }
    }

    Properties properties = new Properties();

    try {
      URI uri = new URI(baseUrl + "/api/translations/" + project + "/" + component + "/" + language + "/file/?q=" + encode(query, StandardCharsets.UTF_8));

      RequestEntity<Void> request = RequestEntity.get(uri).accept(MediaType.TEXT_PLAIN).build();

      if (restTemplate == null) {
        restTemplate = createRestTemplate();
      }

      ResponseEntity<String> response = restTemplate.exchange(request, String.class);

      properties.load(new StringReader(response.getBody()));
    } catch (RestClientException | IOException | URISyntaxException e) {
      logger.warn("Could not load translations for lang " + language, e);
    }

    return properties;
  }

  private Set<Locale> loadLocales() {
    try {
      URI uri = new URI(baseUrl
        + "/api/projects/" + project
        + "/languages/");

      RequestEntity<Void> request = RequestEntity.get(uri).accept(MediaType.APPLICATION_JSON).build();

      if (restTemplate == null) {
        restTemplate = createRestTemplate();
      }

      ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(request, LIST_MAP_STRING_OBJECT);
      if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
        return emptySet();
      }

      return response.getBody().stream()
        .map(WeblateMessageSource::extractLocale)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());

    } catch (RestClientException | URISyntaxException e) {
      logger.warn("Could not load languages", e);
    }
    return emptySet();
  }

  @Override
  protected MessageFormat resolveCode(String code, Locale locale) {
    String value = resolveCodeWithoutArguments(code, locale);
    return value == null ? null : new MessageFormat(value, locale);
  }

  @Override
  protected String resolveCodeWithoutArguments(String code, Locale locale) {
    Properties translations = loadTranslations(locale, false);
    return translations.getProperty(code);
  }

  @Override
  public Properties getAllProperties(Locale locale) {
    Properties allProperties = new Properties();

    Properties translations = loadTranslations(locale, false);
    translations.forEach(allProperties::putIfAbsent);

    MessageSource parentMessageSource = getParentMessageSource();
    if (parentMessageSource instanceof AllPropertiesSource) {
      ((AllPropertiesSource) parentMessageSource).getAllProperties(locale)
        .forEach(allProperties::putIfAbsent);
    }

    return allProperties;
  }

  private static Locale extractLocale(Map<String, Object> entry) {
    Object value = entry.get("code");
    if (value instanceof String) {
      return Locale.forLanguageTag(((String) value).replace("_", "-"));
    }
    return null;
  }

  private static RestTemplate createRestTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    return restTemplate;
  }

}
