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

  // FIX #1: volatile ensures cross-thread visibility without full synchronization
  // on every read; writes are guarded by the intrinsic lock on `this` via
  // synchronized loadCodes().
  private volatile Map<Locale, String> existingLocales;
  private final Map<Locale, CacheEntry> translationsCache = new ConcurrentHashMap<>();
  private final Map<Locale, CombinedCacheEntry> combinedCache = new ConcurrentHashMap<>();

  // FIX #2: one lock object per locale prevents a global lock bottleneck while
  // still stopping concurrent loads for the same locale (cache stampede).
  private final ConcurrentHashMap<Locale, Object> loadingLocks = new ConcurrentHashMap<>();

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
   * parent message source. Only translations newer than this timestamp will ever
   * be fetched from weblate.
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
        .runAsync(this::forceLoadCodes, executor)
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
   * aware</b>: this replaces all interceptors in the {@link RestTemplate}.
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
    combinedCache.clear();
  }

  /**
   * Updates the translations for the given locales from Weblate.
   * <p>
   * Only if the translations from Weblate could be loaded, the translation cache
   * will be updated.
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
   * ensures that newly found locales are available.
   */
  public void removeEmptyCacheEntries() {
    List<Locale> keys = translationsCache.entrySet().stream()
        .filter(e -> e.getValue().properties.isEmpty())
        .map(Entry::getKey)
        .toList();

    keys.forEach(translationsCache::remove);
    combinedCache.clear();
  }

  // FIX #6 (loading improvement): sub-locale cache entries are loaded in parallel
  // via the executor, then joined before building the combined result.
  // FIX #3 (combinedCache order): the combined cache is checked first, before
  // dispatching any per-locale loads, so a fully fresh combined hit costs nothing.
  private Properties loadTranslations(Locale locale, boolean reload) {
    Locale languageOnly = new Locale(locale.getLanguage());
    boolean hasCountry = StringUtils.hasText(locale.getCountry());
    boolean hasVariant = StringUtils.hasText(locale.getVariant()) || StringUtils.hasText(locale.getScript());
    Locale languageAndCountry = hasCountry ? new Locale(locale.getLanguage(), locale.getCountry()) : null;

    // FIX #3: check combined cache before loading any sub-locales.
    // We can only do a meaningful timestamp comparison once we know the current
    // per-code entries, so a quick peek is enough to short-circuit a fully-warm hit.
    if (!reload) {
      CombinedCacheEntry cachedCombined = combinedCache.get(locale);
      if (cachedCombined != null) {
        CacheEntry existingLang = translationsCache.get(languageOnly);
        CacheEntry existingCountry = languageAndCountry != null ? translationsCache.get(languageAndCountry) : null;
        CacheEntry existingVariant = hasVariant ? translationsCache.get(locale) : null;

        long cachedLangTs = existingLang != null ? existingLang.timestamp : 0L;
        long cachedCountryTs = existingCountry != null ? existingCountry.timestamp : 0L;
        long cachedVariantTs = existingVariant != null ? existingVariant.timestamp : 0L;

        long now = System.currentTimeMillis();
        boolean langFresh = existingLang != null && existingLang.timestamp > now - maxAgeMilis;
        boolean countryFresh = !hasCountry || (existingCountry != null && existingCountry.timestamp > now - maxAgeMilis);
        boolean variantFresh = !hasVariant || (existingVariant != null && existingVariant.timestamp > now - maxAgeMilis);

        if (langFresh && countryFresh && variantFresh
            && cachedCombined.languageTimestamp == cachedLangTs
            && cachedCombined.countryTimestamp == cachedCountryTs
            && cachedCombined.variantTimestamp == cachedVariantTs) {
          return cachedCombined.properties;
        }
      }
    }

    // FIX #6: dispatch independent sub-locale loads in parallel on the executor.
    CompletableFuture<CacheEntry> langFuture = CompletableFuture
        .supplyAsync(() -> getOrLoadCodeEntry(languageOnly, reload), executor);
    CompletableFuture<CacheEntry> countryFuture = hasCountry
        ? CompletableFuture.supplyAsync(() -> getOrLoadCodeEntry(languageAndCountry, reload), executor)
        : CompletableFuture.completedFuture(null);
    CompletableFuture<CacheEntry> variantFuture = hasVariant
        ? CompletableFuture.supplyAsync(() -> getOrLoadCodeEntry(locale, reload), executor)
        : CompletableFuture.completedFuture(null);

    CacheEntry langEntry;
    CacheEntry countryEntry;
    CacheEntry variantEntry;

    if (async) {
      // In async mode we return the best available data immediately; the
      // background loads will clear the combinedCache when they complete.
      langEntry = translationsCache.getOrDefault(languageOnly, new CacheEntry(new Properties(), 0L));
      countryEntry = languageAndCountry != null
          ? translationsCache.getOrDefault(languageAndCountry, new CacheEntry(new Properties(), 0L))
          : null;
      variantEntry = hasVariant
          ? translationsCache.getOrDefault(locale, new CacheEntry(new Properties(), 0L))
          : null;

      // clear combined cache once all async loads complete
      CompletableFuture.allOf(langFuture, countryFuture, variantFuture)
          .thenRun(combinedCache::clear);
    } else {
      // Sync mode: wait for all three loads to finish. If a load fails (e.g. HTTP
      // error) fall back to whatever is already cached (or empty), so callers
      // always get a usable result and the exception is already logged inside
      // getOrLoadCodeEntry / loadTranslationData.
      langEntry = joinOrFallback(langFuture,
          translationsCache.getOrDefault(languageOnly, new CacheEntry(new Properties(), 0L)));
      countryEntry = hasCountry
          ? joinOrFallback(countryFuture,
              translationsCache.getOrDefault(languageAndCountry, new CacheEntry(new Properties(), 0L)))
          : null;
      variantEntry = hasVariant
          ? joinOrFallback(variantFuture,
              translationsCache.getOrDefault(locale, new CacheEntry(new Properties(), 0L)))
          : null;
    }

    long countryTimestamp = countryEntry != null ? countryEntry.timestamp : 0L;
    long variantTimestamp = variantEntry != null ? variantEntry.timestamp : 0L;

    Properties result = new Properties();
    result.putAll(langEntry.properties);
    if (countryEntry != null) {
      result.putAll(countryEntry.properties);
    }
    if (variantEntry != null) {
      result.putAll(variantEntry.properties);
    }

    if (!async && result.isEmpty()) {
      logger.info("No translations available for locale " + locale);
    }

    combinedCache.put(locale,
        new CombinedCacheEntry(result, langEntry.timestamp, countryTimestamp, variantTimestamp));
    return result;
  }

  /** Waits for {@code future}; returns {@code fallback} if the future completes exceptionally. */
  private static CacheEntry joinOrFallback(CompletableFuture<CacheEntry> future, CacheEntry fallback) {
    try {
      return future.join();
    } catch (Exception e) {
      // Exception already logged inside getOrLoadCodeEntry / loadTranslationData.
      return fallback;
    }
  }

  // FIX #2: a per-locale lock (one Object per Locale key in loadingLocks) ensures
  // that only one thread fetches a given locale at a time, preventing a stampede,
  // while threads for different locales remain fully concurrent.
  // FIX #2b: a snapshot Properties is passed to the HTTP loader so the existing
  // published CacheEntry is never mutated while other threads read from it.
  private CacheEntry getOrLoadCodeEntry(Locale locale, boolean reload) {
    CacheEntry cacheEntry = translationsCache.get(locale);
    long now = System.currentTimeMillis();

    if (cacheEntry != null && !reload && cacheEntry.timestamp > now - maxAgeMilis) {
      return cacheEntry;
    }

    Object lock = loadingLocks.computeIfAbsent(locale, k -> new Object());
    synchronized (lock) {
      // Re-check inside the lock: another thread may have loaded it while we waited.
      cacheEntry = translationsCache.get(locale);
      now = System.currentTimeMillis();
      if (cacheEntry != null && !reload && cacheEntry.timestamp > now - maxAgeMilis) {
        return cacheEntry;
      }

      // Take a snapshot of existing properties so we never mutate the live entry.
      Properties snapshot = new Properties();
      if (cacheEntry != null) {
        snapshot.putAll(cacheEntry.properties);
      }
      long oldTimestamp = cacheEntry != null ? cacheEntry.timestamp : initialCacheTimestamp;

      boolean success = loadTranslationData(locale, snapshot, oldTimestamp);

      if (success) {
        cacheEntry = new CacheEntry(snapshot, now);
        translationsCache.put(locale, cacheEntry);
      } else if (cacheEntry == null) {
        // FIX #5 (partial): timestamp 0 means "always retry" — consistent with
        // async failure handling below.
        cacheEntry = new CacheEntry(snapshot, 0L);
        translationsCache.put(locale, cacheEntry);
      }
      // If !success && cacheEntry != null: keep the old entry unchanged.

      return cacheEntry;
    }
  }

  /**
   * Resolves and fetches translations for {@code language} from Weblate into
   * {@code properties}, using {@code timestamp} for delta-loading.
   *
   * <p>Returns {@code true} if the load completed successfully (even if no new
   * translations were found), or {@code false} if an error occurred.
   */
  // FIX #4: use the `locales` value returned by loadCodes() directly rather than
  // reading the existingLocales field, and guard against a null map (API failure).
  // FIX #5: in async mode an actual failure now leaves timestamp at 0 (via the
  // entry written in getOrLoadCodeEntry) instead of silently promoting the entry
  // to a fresh timestamp, so the next caller will retry.
  private boolean loadTranslationData(Locale language, Properties properties, long timestamp) {
    // loadCodes() is idempotent and thread-safe; calling it here (synchronously,
    // on the executor thread) is safe and guarantees the locales map is ready.
    Map<Locale, String> locales = loadCodes();

    if (locales == null) {
      // API call failed — treat as load failure.
      return false;
    }

    String lang = locales.get(language);
    if (lang == null) {
      // Locale not known in Weblate — not a failure, just nothing to load.
      return true;
    }

    try {
      fetchTranslationUnits(lang, properties, timestamp);
      return true;
    } catch (Exception e) {
      logger.warn("Error loading translation for locale " + language, e);
      return false;
    }
  }

  private static String formatTimestampIso(long timestamp) {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
    df.setTimeZone(tz);
    return df.format(new Date(timestamp));
  }

  private void fetchTranslationUnits(String code, Properties properties, long timestamp) {
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

  // FIX #1: double-checked locking pattern on the volatile existingLocales field.
  // The first volatile read is cheap (no lock); only threads that see null compete
  // for the intrinsic lock and perform the HTTP call.
  private synchronized Map<Locale, String> forceLoadCodes() {
    existingLocales = null;
    return loadCodes();
  }

  private Map<Locale, String> loadCodes() {
    if (existingLocales != null) {
      return existingLocales;
    }

    synchronized (this) {
      // Second check inside the lock to avoid a redundant HTTP call.
      if (existingLocales != null) {
        return existingLocales;
      }

      RequestEntity<Void> request = RequestEntity.get(baseUrl + "/api/projects/{project}/languages/", project)
          .accept(MediaType.APPLICATION_JSON).build();

      if (restTemplate == null) {
        restTemplate = createRestTemplate();
      }

      try {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(request, LIST_MAP_STRING_OBJECT);
        List<Map<String, Object>> body = response.getBody();
        if (response.getStatusCode().is2xxSuccessful() && body != null) {
          existingLocales = body.stream()
              .filter(this::containsTranslations)
              .map(this::extractCode)
              .filter(Objects::nonNull)
              .collect(Collectors.toMap(this::deriveLocaleFromCode, Function.identity()));
        }
      } catch (Exception e) {
        logger.warn("Error loading locale list from Weblate", e);
        // Leave existingLocales as null so the next call retries.
      }

      return existingLocales;
    }
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

class CombinedCacheEntry {
  final Properties properties;
  final long languageTimestamp;
  final long countryTimestamp;
  final long variantTimestamp;

  CombinedCacheEntry(Properties properties, long languageTimestamp, long countryTimestamp, long variantTimestamp) {
    this.properties = properties;
    this.languageTimestamp = languageTimestamp;
    this.countryTimestamp = countryTimestamp;
    this.variantTimestamp = variantTimestamp;
  }
}
