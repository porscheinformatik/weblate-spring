package at.porscheinformatik.weblate.spring;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;

import java.net.URI;
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
import java.util.concurrent.TimeUnit;
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
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonCreator;
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
public class WeblateMessageSource extends AbstractMessageSource implements AllPropertiesSource, AutoCloseable {
  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_STRING_OBJECT = new ParameterizedTypeReference<>() {
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
  /**
   * Cache keyed by exact Weblate-derived locale (e.g. {@code en}, {@code en_US}, {@code en_US_POSIX}).
   * Each entry only holds the translations fetched for that specific language code.
   * Entries are combined on read, so timestamps stay consistent per language code.
   */
  private final Map<Locale, CacheEntry> translationsCache = new ConcurrentHashMap<>();
  /**
   * Merged view cache keyed by the requested locale (e.g. {@code en_US}).
   * Built by combining all relevant per-code cache levels. Invalidated whenever any
   * constituent level's timestamp has changed since the merge, so the hot path
   * (all levels fresh) returns this directly without iterating any {@link Properties}.
   */
  private final Map<Locale, MergedCacheEntry> mergedCache = new ConcurrentHashMap<>();
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
   * Use async loading - all operations will be performed in a single threaded
   * {@link ExecutorService}.
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
    return this.existingLocales == null ? emptySet() : this.existingLocales.keySet();
  }

  /**
   * Reload the cached list of existing locales.
   * <p>
   * This does not clear the cached translations.
   */
  public void reloadExistingLocales() {
    CompletableFuture<Void> loadTask = CompletableFuture
        .runAsync(this::loadCodes, executor)
        .handle((val, e) -> {
          if (e != null) {
            logger.warn("Error reloading locales", e);
          }
          return val;
        });
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
    mergedCache.clear();
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
        .toList();

    keys.forEach(translationsCache::remove);
  }

  /**
   * Returns the combined translations for the given locale by merging cache entries
   * for each locale level (language-only → language+country → full locale) in order
   * of increasing specificity.  More specific entries take precedence.
   *
   * <p>Each locale level has its own {@link CacheEntry} with an independent timestamp,
   * so delta-loading remains consistent: a country-specific cache entry is never
   * polluted with a stale timestamp from the language-only level.</p>
   */
  private Properties loadTranslations(Locale locale, boolean reload) {
    long now = System.currentTimeMillis();

    // Determine the locale levels to load, from least to most specific.
    // Always at least the language-only level; optionally language+country and full locale.
    Locale languageOnly = new Locale(locale.getLanguage());
    Locale languageAndCountry = StringUtils.hasText(locale.getCountry())
        ? new Locale(locale.getLanguage(), locale.getCountry())
        : null;
    boolean hasVariantOrScript = StringUtils.hasText(locale.getVariant()) || StringUtils.hasText(locale.getScript());

    // Refresh stale levels independently.
    refreshCacheEntry(languageOnly, now, reload);
    if (languageAndCountry != null && !languageOnly.equals(languageAndCountry)) {
      refreshCacheEntry(languageAndCountry, now, reload);
    }
    if (hasVariantOrScript) {
      refreshCacheEntry(locale, now, reload);
    }

    // Hot path: return the previously merged result if all constituent level cache
    // entries are the same instances as when it was built (identity check catches both
    // expiry-triggered refreshes and async loads that replaced a level entry).
    MergedCacheEntry existing = mergedCache.get(locale);
    if (existing != null && existing.isStillValid(translationsCache, languageOnly, languageAndCountry,
        hasVariantOrScript ? locale : null)) {
      return existing.properties;
    }

    // Build a fresh merge from least-specific to most-specific (later entries win).
    Properties combined = new Properties();
    mergeInto(combined, languageOnly);
    if (languageAndCountry != null && !languageOnly.equals(languageAndCountry)) {
      mergeInto(combined, languageAndCountry);
    }
    if (hasVariantOrScript) {
      mergeInto(combined, locale);
    }

    mergedCache.put(locale, new MergedCacheEntry(combined, translationsCache, languageOnly,
        languageAndCountry, hasVariantOrScript ? locale : null));

    if (!async && combined.isEmpty()) {
      logger.info("No translations available for locale " + locale);
    }

    return combined;
  }

  /**
   * Puts all entries from the cache for {@code level} into {@code target}.
   * More-specific callers should invoke this <em>after</em> less-specific ones;
   * existing keys in {@code target} are <em>overwritten</em> so that the most-specific
   * value always wins.
   */
  private void mergeInto(Properties target, Locale level) {
    CacheEntry entry = translationsCache.get(level);
    if (entry != null) {
      target.putAll(entry.properties);
    }
  }

  /**
   * Ensures the {@link CacheEntry} for {@code level} is populated and up-to-date.
   * If the entry is absent or expired (or a forced reload is requested) the
   * translations for exactly this locale level are fetched from Weblate and the
   * entry is replaced atomically.
   */
  private void refreshCacheEntry(Locale level, long now, boolean reload) {
    CacheEntry existing = translationsCache.get(level);

    if (existing != null && !reload && existing.timestamp > now - maxAgeMilis) {
      return; // still fresh
    }

    long oldTimestamp = existing != null ? existing.timestamp : initialCacheTimestamp;

    // Start with a flat copy of the previously cached properties so that delta-loading
    // (which only returns changed/added entries) can add on top without losing old data.
    Properties copy = new Properties();
    if (existing != null) {
      copy.putAll(existing.properties);
    }

    // Replace the entry with the current timestamp before loading so that concurrent
    // requests see a "loading in progress" entry rather than triggering duplicate loads.
    CacheEntry updated = new CacheEntry(copy, now);
    translationsCache.put(level, updated);

    loadTranslation(level, updated.properties, oldTimestamp);
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
    CompletableFuture<Map<Locale, String>> loadCodesTask = CompletableFuture.supplyAsync(this::loadCodes);

    CompletableFuture<Void> loadTask = loadCodesTask.thenCompose(locales -> {
      String lang = existingLocales.get(language);
      if (lang != null) {
        return CompletableFuture.runAsync(() -> loadTranslation(lang, properties, timestamp), executor);
      }
      return CompletableFuture.completedStage(null);
    }).handle((val, e) -> {
      if (e != null) {
        logger.warn("Error loading translation for locale " + language, e);
      }
      // Replace the CacheEntry with a fresh instance so that identity-based checks in
      // MergedCacheEntry.isStillValid() detect the completion of this load and force a
      // merged-cache rebuild on the next read (important for async mode).
      CacheEntry current = translationsCache.get(language);
      if (current != null) {
        translationsCache.put(language, new CacheEntry(current.properties, current.timestamp));
      }
      return val;
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

  private void loadTranslation(String code, Properties properties, long timestamp) {
    String currentQuery = query;
    if (timestamp > 0L) {
      String timestampStr = formatTimestampIso(timestamp);
      currentQuery += " AND (added:>=" + timestampStr + " OR changed:>=" + timestampStr + ")";
    }
    RequestEntity<Void> request = RequestEntity
        .get(baseUrl + "/api/translations/{project}/{component}/{languageCode}/units/?q={query}",
            project, component, code, currentQuery)
        .accept(MediaType.APPLICATION_JSON)
        .build();

    if (restTemplate == null) {
      restTemplate = createRestTemplate();
    }

    while (request != null) {

      ResponseEntity<UnitsResponse> response = restTemplate.exchange(request, UnitsResponse.class);
      UnitsResponse body = response.getBody();
      if (!response.getStatusCode().is2xxSuccessful() || body == null) {
        logger.warn(String.format("Got empty or non-200 response (status=%s, body=%s)", response.getStatusCode(),
            response.getBody()));
        break;
      }

      for (Unit unit : body.results) {
        properties.put(unit.code, unit.target[0]);
      }

      // check for next request
      if (body.next != null) {
        request = RequestEntity.get(body.next).accept(MediaType.APPLICATION_JSON).build();
      } else {
        request = null;
      }
    }
  }

  private Map<Locale, String> loadCodes() {
    if (existingLocales != null) {
      return existingLocales;
    }

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
    if (parentMessageSource instanceof AllPropertiesSource messageSource) {
      messageSource.getAllProperties(locale).forEach(allProperties::putIfAbsent);
    }

    return allProperties;
  }

  /**
   * Shuts down the executor service.
   */
  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private boolean containsTranslations(Map<String, Object> entry) {
      Object translatedCount = entry.get("translated");
      return translatedCount instanceof Integer translatedCountInt && translatedCountInt > 0;
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
  final URI next;
  final List<Unit> results;

  @JsonCreator
  public UnitsResponse(@JsonProperty("next") URI next, @JsonProperty("results") List<Unit> results) {
    this.next = next;
    this.results = results;
  }
}

class Unit {
  @JsonProperty("context")
  final String code;
  final String[] target;

  @JsonCreator
  public Unit(@JsonProperty("code") String code, @JsonProperty("target") String[] target) {
    this.code = code;
    this.target = target;
  }
}

class CacheEntry {
  final Properties properties;
  final long timestamp;

  CacheEntry(Properties properties, long timestamp) {
    this.properties = properties;
    this.timestamp = timestamp;
  }
}

/**
 * Cached merged view for a specific requested locale (e.g. {@code en_US}).
 * Stores the merged {@link Properties} together with the identity of the
 * per-code {@link CacheEntry} levels used to build it.
 * <p>
 * The merged result is valid as long as every constituent level's current
 * {@link CacheEntry} instance in {@code translationsCache} is the same object
 * as the one used at build time.  {@code refreshCacheEntry} always puts a new
 * {@link CacheEntry} instance when a level is refreshed, and {@code loadTranslation}
 * replaces the entry (again with a new instance) once an async fetch completes.
 * Either event causes {@link #isStillValid} to return {@code false}, forcing a rebuild.
 */
class MergedCacheEntry {
  final Properties properties;
  /** Snapshot of per-level CacheEntry instances at merge time, keyed by locale level. */
  private final Map<Locale, CacheEntry> levelEntries;

  MergedCacheEntry(Properties properties, Map<Locale, CacheEntry> translationsCache,
      Locale languageOnly, Locale languageAndCountry, Locale full) {
    this.properties = properties;
    this.levelEntries = new HashMap<>();
    snapshot(translationsCache, languageOnly);
    if (languageAndCountry != null) {
      snapshot(translationsCache, languageAndCountry);
    }
    if (full != null) {
      snapshot(translationsCache, full);
    }
  }

  private void snapshot(Map<Locale, CacheEntry> cache, Locale level) {
    levelEntries.put(level, cache.get(level)); // may be null if level has no entry
  }

  boolean isStillValid(Map<Locale, CacheEntry> translationsCache,
      Locale languageOnly, Locale languageAndCountry, Locale full) {
    if (!sameEntry(translationsCache, languageOnly)) {
      return false;
    }
    if (languageAndCountry != null && !sameEntry(translationsCache, languageAndCountry)) {
      return false;
    }
    return full == null || sameEntry(translationsCache, full);
  }

  private boolean sameEntry(Map<Locale, CacheEntry> cache, Locale level) {
    return cache.get(level) == levelEntries.get(level); // intentional identity check
  }
}