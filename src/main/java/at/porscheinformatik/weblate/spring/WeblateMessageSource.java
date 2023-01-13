package at.porscheinformatik.weblate.spring;

import static java.util.Collections.singletonList;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@link MessageSource} loading texts from Weblate translation server via REST
 * API.
 *
 * <p>
 * If you use the {@link WeblateMessageSource} with a parent
 * {@link org.springframework.context.support.ReloadableResourceBundleMessageSource}
 * and want to resolve all properties you can use the
 * {@link AllPropertiesReloadableResourceBundleMessageSource}
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
  private long maxAgeMilis = 30L * 60L * 1000L; // 30 minutes
  private long initialCacheTimestamp = 0;
  private boolean async = false;
  private Map<String, Locale> codeToLocale = new HashMap<>();

  private Map<Locale, String> existingLocales;
  private final Map<Locale, CacheEntry> translationsCache = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors
      .newSingleThreadExecutor(r -> new Thread(r, "WeblateMessageSource"));

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
   * Set the Weblate query for extracting the texts. Default is
   * "state:&gt;=translated"
   * <p>
   * See also:
   * <a href="https://docs.weblate.org/en/latest/user/search.html">Weblate
   * Search</a>.
   *
   * @param query the Weblate query
   */
  public void setQuery(String query) {
    this.query = query;
  }

  /** @return the max age for items in the cache (in milliseconds) */
  public long getMaxAgeMilis() {
    return maxAgeMilis;
  }

  /**
   * Sets the max age for items in the cache (in milliseconds).
   *
   * @param maxAgeMilis the max age for items in the cache (in milliseconds)
   */
  public void setMaxAgeMilis(long maxAgeMilis) {
    this.maxAgeMilis = maxAgeMilis;
  }

  /**
   * @return the initial cache timestamp
   */
  public long getInitialCacheTimestamp() {
    return initialCacheTimestamp;
  }

  /**
   * Set the timestamp that is used when no cache entry is set. Default is 0.
   * <p>
   * This can be used when you have bundled translations that are provided via a
   * parent message
   * source. Only translations newer than this timestamp will ever be fetched from
   * weblate.
   *
   * @param initialCacheTimestamp the initial cache timestamp
   */
  public void setInitialCacheTimestamp(long initialCacheTimestamp) {
    this.initialCacheTimestamp = initialCacheTimestamp;
  }

  /**
   * @return if async enabled
   */
  public boolean isAsync() {
      return async;
  }

  /**
   * Use async loading - all operations will be performed in a single threaded {@link ExecutorService}.
   * 
   * @param async if true loading will be performed asynchronously
   */
  public void setAsync(boolean async) {
      this.async = async;
  }

  /**
   * @return all existing locales of the configured weblate component
   */
  public Set<Locale> getExistingLocales() {
    return this.existingLocales.keySet();
  }

  /**
   * Reload the cached list of existing locales.
   * <p>
   * This does not clear the cached translations.
   */
  public void reloadExistingLocales() {
    CompletableFuture<Void> loadTask = CompletableFuture.runAsync(() -> loadCodes(), executor);
    if (!async) {
      loadTask.join();
    }
  }

  /**
   * Set the {@link RestTemplate} to use for getting data from Weblate REST API.
   * <p>
   * Please configure the given parameter with UTF-8 as the standard message
   * converter:
   * 
   * <pre>
   * <code>restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));</code>
   * </pre>
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
   * Sets {@link WeblateAuthenticationInterceptor} for calling the REST API. <b>Be
   * aware</b>: this
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
   * Registers a manual mapping of a Weblate code to a {@link Locale}.
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
    existingLocales = null;
    translationsCache.clear();
  }

  /**
   * Updates the translations for the given locales from Weblate.
   * <p>
   * Only if the translations from Weblate could be loaded, the translation cache
   * will be updated
   *
   * @param locales the locales that should be reloaded
   */
  public void reload(Locale... locales) {
    logger.info("Going to reload the translations ...");

    if (locales != null) {
      for (Locale locale : locales) {
        logger.info(String.format("Reload translation for locale %s", locale));
        loadTranslations(locale, true);
      }
    }
  }

  /**
   * Remove cache entries that do not contain a translation.
   * <p>
   * This is useful e.g. when called after updating the existing locales. It
   * ensures
   * that newly found locales are available.
   */
  public void removeEmptyCacheEntries() {
    List<Locale> keys = translationsCache.entrySet().stream()
        .filter(e -> e.getValue().properties.isEmpty())
        .map(Entry::getKey)
        .collect(Collectors.toList());

    keys.forEach(translationsCache::remove);
  }

  private Properties loadTranslations(Locale locale, boolean reload) {
    CacheEntry cacheEntry = translationsCache.get(locale);
    long now = System.currentTimeMillis();

    if (cacheEntry != null && !reload && cacheEntry.timestamp > now - maxAgeMilis) {
      return cacheEntry.properties;
    }

    Properties properties = cacheEntry != null ? cacheEntry.properties : new Properties();
    long oldTimestamp = cacheEntry != null ? cacheEntry.timestamp : initialCacheTimestamp;

    cacheEntry = new CacheEntry(properties, now);

    Locale languageOnly = new Locale(locale.getLanguage());
    CacheEntry languageCacheEntry = translationsCache.get(languageOnly);
    if (languageCacheEntry != null && !reload && languageCacheEntry.timestamp > now - maxAgeMilis) {
      cacheEntry.properties.putAll(languageCacheEntry.properties);
    } else {
      loadTranslation(new Locale(locale.getLanguage()), cacheEntry.properties, oldTimestamp);
    }

    if (StringUtils.hasText(locale.getCountry())) {
      loadTranslation(new Locale(locale.getLanguage(), locale.getCountry()), cacheEntry.properties, oldTimestamp);
    }

    if (StringUtils.hasText(locale.getVariant()) || StringUtils.hasText(locale.getScript())) {
      loadTranslation(locale, cacheEntry.properties, oldTimestamp);
    }

    translationsCache.put(locale, cacheEntry);

    if (cacheEntry.properties.size() == 0) {
      logger.info("No translations available for locale " + locale);
    }

    return cacheEntry.properties;
  }

  /**
   * Load translations for a locale into the properties.
   *
   * @param language   the locale to load the translations for
   * @param properties where the translations are added
   * @param timestamp  where only translations newer than this timestamp are
   *                   loaded (optional)
   */
  private void loadTranslation(Locale language, Properties properties, long timestamp) {
    CompletableFuture<Map<Locale, String>> loadCodesTask = CompletableFuture.supplyAsync(() -> loadCodes());

    CompletableFuture<Void> loadTask = loadCodesTask.thenCompose(locales -> {
      String lang = existingLocales.get(language);
      if (lang != null) {
        return loadTranslation(lang, properties, timestamp);
      }
      return CompletableFuture.completedStage(null);
    });

    if (!async) {
      loadTask.join();
    }
  }

  private static String formatTimestampIso(long timestamp) {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
    df.setTimeZone(tz);
    return df.format(new Date(timestamp));
  }

  private CompletableFuture<Void> loadTranslation(String code, Properties properties, long timestamp) {
    return CompletableFuture.runAsync(() -> {
      String currentQuery = query;
      if (timestamp > 0L) {
        String timestampStr = formatTimestampIso(timestamp);
        currentQuery += " AND (added:>=" + timestampStr + " OR changed:>=" + timestampStr + ")";
      }

      try {
        RequestEntity<Void> request = RequestEntity
            .get(baseUrl + "/api/translations/{project}/{component}/{languageCode}/units/?q={query}",
                project, component, code, currentQuery)
            .accept(MediaType.APPLICATION_JSON)
            .build();

        if (restTemplate == null) {
          restTemplate = createRestTemplate();
        }

        UnitsResponse responseBody;
        while (true) {
          ResponseEntity<UnitsResponse> response = restTemplate.exchange(request, UnitsResponse.class);
          responseBody = response.getBody();
          if (!response.getStatusCode().is2xxSuccessful() || responseBody == null) {
            logger.warn(String.format("Got empty or non-200 response (status=%s, body=%s)", response.getStatusCode(),
                response.getBody()));
            break;
          }

          for (Unit unit : responseBody.results) {
            properties.put(unit.code, unit.target[0]);
          }

          if (responseBody.next == null) {
            break;
          }

          request = RequestEntity.get(responseBody.next.toURI()).accept(MediaType.APPLICATION_JSON).build();
        }
      } catch (RestClientException | URISyntaxException e) {
        logger.warn("Could not load translations (code=" + code + ")", e);
      }
    }, executor);
  }

  private Map<Locale, String> loadCodes() {
    if (existingLocales != null) {
      return existingLocales;
    }

    try {
      RequestEntity<Void> request = RequestEntity.get(baseUrl + "/api/projects/{project}/languages/", project)
          .accept(MediaType.APPLICATION_JSON).build();

      if (restTemplate == null) {
        restTemplate = createRestTemplate();
      }

      ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(request, LIST_MAP_STRING_OBJECT);
      List<Map<String, Object>> body = response.getBody();
      if (response.getStatusCode().is2xxSuccessful() && body != null) {
        existingLocales = body.stream()
            .filter(this::containsTranslations)
            .map(this::extractCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(this::deriveLocaleFromCode, Function.identity()));
      }
    } catch (RestClientException e) {
      logger.warn("Could not load languages", e);
    }
    return existingLocales;
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

  private boolean containsTranslations(Map<String, Object> entry) {
    Object translatedCount = entry.get("translated");
    return translatedCount instanceof Integer && ((Integer) translatedCount) > 0;
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
        return null;
      } else {
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

class UnitsResponse {
  public URL next;
  public List<Unit> results;
}

class Unit {
  @JsonProperty("context")
  public String code;
  public String[] target;
}

class CacheEntry {
  final Properties properties;
  final long timestamp;

  CacheEntry(Properties properties, long timestamp) {
    this.properties = properties;
    this.timestamp = timestamp;
  }
}