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
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
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
  private Map<String, Locale> codeToLocale = new HashMap<>();

  private Map<Locale, String> existingLocales;
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
   * @return the manual mapping Weblate codes to {@link Locale}s
   */
  public Map<String, Locale> getCodeToLocale() {
    return codeToLocale;
  }

  /**
   * Set the manual mapping of Weblate codes to {@link Locale}s.
   *
   * @param codeToLocale the mapping
   */
  public void setCodeToLocale(Map<String, Locale> codeToLocale) {
    this.codeToLocale = codeToLocale;
  }

  /**
   * Registers a manual mapping of w Weblate code to a {@link Locale}.
   *
   * @param code   the Weblate language code
   * @param locale a {@link Locale} with
   */
  public void registerLocaleMapping(String code, Locale locale) {
    codeToLocale.put(code, locale);
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
      Properties countrySpecific = loadTranslation(new Locale(locale.getLanguage(), locale.getCountry()));
      translations.putAll(countrySpecific);
    }

    if (StringUtils.hasText(locale.getVariant()) || StringUtils.hasText(locale.getScript())) {
      Properties variantSpecific = loadTranslation(locale);
      translations.putAll(variantSpecific);
    }

    translationsCache.put(locale, translations);

    return translations;
  }

  private Properties loadTranslation(Locale language) {

    synchronized (existingLocalesLock) {
      if (existingLocales == null) {
        existingLocales = loadCodes();
      }
    }

    return Optional.ofNullable(existingLocales.get(language))
      .map(this::loadTranslation)
      .orElseGet(() -> {
        logger.info("No code registered for Locale " + language);
        return new Properties();
      });
  }

  private Properties loadTranslation(String code) {

    Properties properties = new Properties();

    try {
      URI uri = new URI(baseUrl + "/api/translations/" + project + "/" + component + "/" + code + "/file/?q=" + encode(query, StandardCharsets.UTF_8));

      RequestEntity<Void> request = RequestEntity.get(uri).accept(MediaType.TEXT_PLAIN).build();

      if (restTemplate == null) {
        restTemplate = createRestTemplate();
      }

      ResponseEntity<String> response = restTemplate.exchange(request, String.class);

      if (response.getStatusCode().is2xxSuccessful() && response.hasBody()) {
        properties.load(new StringReader(response.getBody()));
      } else {
        logger.warn("Got empty or non-200 response (status=" + response.getStatusCode() + ",body=" + response.getBody() + ")");
      }
    } catch (RestClientException | IOException | URISyntaxException e) {
      logger.warn("Could not load translations (code=" + code + ")", e);
    }

    return properties;
  }

  private Map<Locale, String> loadCodes() {
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
        return emptyMap();
      }

      return response.getBody().stream()
        .map(this::extractCode)
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(this::deriveLocaleFromCode, Function.identity()));

    } catch (RestClientException | URISyntaxException e) {
      logger.warn("Could not load languages", e);
    }
    return emptyMap();
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

  private String extractCode(Map<String, Object> entry) {
    return Optional.ofNullable(entry.get("code"))
      .filter(String.class::isInstance)
      .map(String.class::cast)
      .orElse(null);
  }

  private Locale deriveLocaleFromCode(String code) {
    if (codeToLocale.containsKey(code)) {
      return codeToLocale.get(code);
    }

    final Locale locale = WeblateUtils.deriveLocaleFromCode(code);
    if (locale == null) {
      logger.warn(String.format("Could not derive a Locale for code[%s], " +
        "consider adding it with weblateMessageSource.registerLocaleMapping", code));
      return null;
    } else {
      String mappedCode = findCodeMapping(locale);
      if (mappedCode != null) {
        logger.warn(String.format("derived Locale[%s] from code[%s], but Locale was already registered for code[%s]",
          locale, code, mappedCode));
        return  null;
      }
      else {
        logger.debug(String.format("derived Locale[%s] from code[%s]", locale, code));
        return locale;
      }
    }
  }

  private String findCodeMapping(Locale locale) {
    for (Map.Entry<String, Locale> entry : codeToLocale.entrySet()) {
      if (entry.getValue().equals(locale)) {
        return entry.getKey();
      }
    }
    return null;
  }

  private static RestTemplate createRestTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    return restTemplate;
  }
}
