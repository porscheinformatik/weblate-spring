package at.porscheinformatik.weblate.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.Locale;
import java.util.Properties;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestTemplate;


class WeblateMessageSourceTest {

  private static final String TEXT1 = "Hello, World!";
  private static final String TEXT1_CHANGED = "Another one";
  private static final String TEXT2 = "Wow this works";

  private static final String RESPONSE_OK = "{\"count\":2,\"next\":null,\"previous\":null,\"results\":["
      + "{\"id\":1,\"context\":\"key1\",\"source\":[\"" + TEXT1 + "\"],\"target\":[\"" + TEXT1 + "\"]},"
      + "{\"id\":2,\"context\":\"key2\",\"source\":[\"" + TEXT2 + "\"],\"target\":[\"" + TEXT2 + "\"]}"
      + "]}";
  private static final String RESPONSE_OK_CHANGED = "{\"count\":2,\"next\":null,\"previous\":null,\"results\":["
      + "{\"id\":1,\"context\":\"key1\",\"source\":[\"" + TEXT1 + "\"],\"target\":[\"" + TEXT1_CHANGED + "\"]}"
      + "]}";
  private static final String RESPONSE_PAGING = "{\"count\":2,\"next\":\"http://localhost:8080/api/translations/test-project/test-comp/en/units/?page=1&q=state%3A%3E%3Dtranslated\",\"previous\":null,\"results\":[]}";
  private static final String RESPONSE_EMPTY = "{\"count\":0,\"next\":null,\"previous\":null,\"results\":[]}";

  private final RestTemplate restTemplate = new RestTemplate();

  private WeblateMessageSource messageSource;

  private MockRestServiceServer mockServer;

  @BeforeEach
  void init() {
    messageSource = new WeblateMessageSource();
    messageSource.setRestTemplate(restTemplate);
    messageSource.setProject("test-project");
    messageSource.setComponent("test-comp");
    messageSource.setBaseUrl("http://localhost:8080");

    mockServer = MockRestServiceServer.createServer(restTemplate);
  }

  @AfterEach
  void tearDown() {
    messageSource.close();
    mockServer.verify();
  }

  private void mockGetLocales() {
    mockGetLocales(HttpStatus.OK);
  }

  private void mockGetLocales(HttpStatus status) {
    mockServer.expect(ExpectedCount.once(),
        requestTo("http://localhost:8080/api/projects/test-project/languages/")).andRespond(
            withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("[{\"code\":\"en\", \"translated\":1},{\"code\":\"de\"}]"));
  }

  private void mockGetLocalesWithCountry() {
    mockServer.expect(ExpectedCount.once(),
        requestTo("http://localhost:8080/api/projects/test-project/languages/")).andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("[{\"code\":\"en\", \"translated\":1},{\"code\":\"en_US\", \"translated\":1}]"));
  }

  private void mockGetLocalesWithFallback() {
    mockServer.expect(ExpectedCount.once(),
        requestTo("http://localhost:8080/api/projects/test-project/languages/")).andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("[{\"code\":\"en\", \"translated\":1},{\"code\":\"de\", \"translated\":1}]"));
  }

  private void mockResponseForCode(String languageCode, String body) {
    mockResponseForCode(languageCode, body, HttpStatus.OK);
  }

  private void mockResponseForCode(String languageCode, String body, HttpStatus status) {
    try {
      String url = "http://localhost:8080/api/translations/test-project/test-comp/" + languageCode + "/units/";
      DefaultResponseCreator response = withStatus(status).contentType(MediaType.APPLICATION_JSON);
      if (body != null) {
        response.body(body);
      }
      mockServer.expect(ExpectedCount.once(),
          requestTo(Matchers.startsWith(url)))
          .andExpect(method(HttpMethod.GET))
          .andRespond(response);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void mockResponse(String body) {
    mockResponse(body, HttpStatus.OK);
  }

  private void mockResponse(String body, HttpStatus status) {
    try {
      String url = "http://localhost:8080/api/translations/test-project/test-comp/en/units/";
      DefaultResponseCreator response = withStatus(status).contentType(MediaType.APPLICATION_JSON);
      if (body != null) {
        response.body(body);
      }
      mockServer.expect(ExpectedCount.once(),
          requestTo(Matchers.startsWith(url)))
          .andExpect(method(HttpMethod.GET))
          .andRespond(response);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void reloadExistingLocales() {
    mockGetLocales();
    messageSource.reloadExistingLocales();
    assertTrue(messageSource.getExistingLocales().contains(Locale.ENGLISH));
    assertFalse(messageSource.getExistingLocales().contains(Locale.GERMAN));
  }

  @Test
  void handleErrorInReloadExistingLocales() {
    mockGetLocales(HttpStatus.INTERNAL_SERVER_ERROR);

    messageSource.reloadExistingLocales();
    assertTrue(messageSource.getExistingLocales().isEmpty());
  }

  @Test
  void simpleCase() {
    mockGetLocales();
    mockResponse(RESPONSE_OK);

    String key1Value = messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH);
    assertEquals(TEXT1, key1Value);
    key1Value = messageSource.resolveCodeWithoutArguments("key1", Locale.US);
    assertEquals(TEXT1, key1Value);
    key1Value = messageSource.resolveCodeWithoutArguments("key1", Locale.US);
    assertEquals(TEXT1, key1Value);
  }

  @Test
  void emptyResponse() {
    mockGetLocales();
    mockResponse(RESPONSE_EMPTY);

    String key1Value = messageSource.getAllProperties(Locale.ENGLISH).getProperty("key1");

    assertNull(key1Value);
    mockServer.verify();
  }

  @Test
  void clearCache() {
    mockGetLocales();
    mockResponse(RESPONSE_OK);
    mockGetLocales();
    mockResponse(RESPONSE_OK.replace(TEXT1, TEXT1_CHANGED));

    Properties allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals(TEXT1, allProperties.get("key1"));
    messageSource.clearCache();
    allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals(TEXT1_CHANGED, allProperties.get("key1"));
  }

  @Test
  void reloadLocale() {
    mockGetLocales();
    mockResponse(RESPONSE_OK);
    mockResponse(RESPONSE_OK_CHANGED);

    assertEquals(TEXT1, messageSource.getMessage("key1", null, Locale.ENGLISH));
    messageSource.reload(Locale.ENGLISH);
    assertEquals(TEXT1_CHANGED, messageSource.getMessage("key1", null, Locale.ENGLISH));
    assertEquals(TEXT2, messageSource.getMessage("key2", null, Locale.ENGLISH));
  }

  @Test
  void paging() {
    mockGetLocales();
    mockResponse(RESPONSE_PAGING);
    mockResponse(RESPONSE_PAGING); // try a second paging call
    mockResponse(RESPONSE_OK);

    Properties allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals(TEXT1, allProperties.get("key1"));
  }

  @Test
  @SuppressWarnings("java:S2925")
  void cacheTimeout() throws Exception {
    mockGetLocales();
    mockResponse(RESPONSE_OK);
    mockResponse(RESPONSE_OK_CHANGED);

    assertEquals(TEXT1, messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
    assertEquals(TEXT1, messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
    assertEquals(TEXT2, messageSource.resolveCodeWithoutArguments("key2", Locale.ENGLISH));

    messageSource.setMaxAgeMilis(1000);

    Thread.sleep(1001);

    assertEquals(TEXT1_CHANGED, messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
    assertEquals(TEXT2, messageSource.resolveCodeWithoutArguments("key2", Locale.ENGLISH));
  }

  @Test
  @SuppressWarnings("java:S2925")
  void asyncLoading() throws Exception {
    messageSource.setAsync(true);
    MessageSource parentMessageSource = Mockito.mock(MessageSource.class);
    messageSource.setParentMessageSource(parentMessageSource);
    when(parentMessageSource.getMessage("key1", null, null, Locale.ENGLISH)).thenReturn("Before async finished");

    mockGetLocales();
    mockResponse(RESPONSE_OK);

    String key1Value = messageSource.getMessage("key1", null, Locale.ENGLISH);
    assertEquals("Before async finished", key1Value);
    
    Thread.sleep(500);

    key1Value = messageSource.getMessage("key1", null, Locale.ENGLISH);
    assertEquals(TEXT1, key1Value);
  }

  @Test
  void handlesHttpErrorLoadingTranslations() {
    mockGetLocales();
    mockResponse(null, HttpStatus.UNAUTHORIZED);

    assertThrows(NoSuchMessageException.class, () -> messageSource.getMessage("key1", null, Locale.ENGLISH));
  }

  @Test
  void handlesEmptyBody() {
    mockGetLocales();
    mockResponse("", HttpStatus.OK);

    assertThrows(NoSuchMessageException.class, () -> messageSource.getMessage("key1", null, Locale.ENGLISH));
  }

  @Test
  void handlesHttpErrorLoadingLanguages() {
    mockGetLocales(HttpStatus.NOT_FOUND);

    assertThrows(NoSuchMessageException.class, () -> messageSource.getMessage("key1", null, Locale.ENGLISH));
  }

  @Test
  void useAuthentication() {
    messageSource.useAuthentication("authtoken");
    mockGetLocales();
    mockResponse(RESPONSE_OK);

    assertEquals(TEXT1, messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
  }

  @Test
  void allPropertiesWithParent() {
    var parent = Mockito.mock(AllPropertiesReloadableResourceBundleMessageSource.class);
    var properties = new Properties();

    when(parent.getAllProperties(Locale.ENGLISH)).thenReturn(properties);
    messageSource.setParentMessageSource(parent);
    messageSource.getAllProperties(Locale.ENGLISH);

    assertEquals(properties, messageSource.getAllProperties(Locale.ENGLISH));
  }

  @Test
  void defaultFallbackLocale() {
    String enResponse = "{\"count\":2,\"next\":null,\"previous\":null,\"results\":["
        + "{\"id\":1,\"context\":\"key1\",\"target\":[\"en-key1\"]},"
        + "{\"id\":2,\"context\":\"key2\",\"target\":[\"en-key2\"]}"
        + "]}";
    String deResponse = "{\"count\":1,\"next\":null,\"previous\":null,\"results\":["
        + "{\"id\":1,\"context\":\"key1\",\"target\":[\"de-key1\"]}"
        + "]}";

    messageSource.setDefaultFallbackLocale(Locale.ENGLISH);
    mockGetLocalesWithFallback();
    mockResponseForCode("en", enResponse);
    mockResponseForCode("de", deResponse);

    Properties allProperties = messageSource.getAllProperties(Locale.GERMAN);

    assertEquals("de-key1", allProperties.getProperty("key1"));
    assertEquals("en-key2", allProperties.getProperty("key2"));
    assertEquals("de-key1", messageSource.resolveCodeWithoutArguments("key1", Locale.GERMAN));
    assertEquals("en-key2", messageSource.resolveCodeWithoutArguments("key2", Locale.GERMAN));
  }

  /**
   * Verifies that each locale level (language-only vs. language+country) has its own
   * independent cache entry and independent delta-loading timestamp.
   * <p>
   * When {@code en_US} is requested, the {@code en} and {@code en_US} caches are
   * loaded independently.  After a cache timeout only the expired level is re-fetched
   * with its own {@code oldTimestamp}, keeping the two levels consistent.
   * Country-specific keys must override language-only keys.
   */
  @Test
  @SuppressWarnings("java:S2925")
  void perCodeCacheConsistency() throws Exception {
    // "key1" exists in both en and en_US; "key2" only in en; "key3" only in en_US
    String enResponse = "{\"count\":2,\"next\":null,\"previous\":null,\"results\":["
        + "{\"id\":1,\"context\":\"key1\",\"target\":[\"en-key1\"]},"
        + "{\"id\":2,\"context\":\"key2\",\"target\":[\"en-key2\"]}"
        + "]}";
    String enUsResponse = "{\"count\":2,\"next\":null,\"previous\":null,\"results\":["
        + "{\"id\":1,\"context\":\"key1\",\"target\":[\"en_US-key1\"]},"
        + "{\"id\":3,\"context\":\"key3\",\"target\":[\"en_US-key3\"]}"
        + "]}";
    String enResponseAfterTimeout = "{\"count\":1,\"next\":null,\"previous\":null,\"results\":["
        + "{\"id\":2,\"context\":\"key2\",\"target\":[\"en-key2-updated\"]}"
        + "]}";
    String enUsResponseAfterTimeout = RESPONSE_EMPTY;

    mockGetLocalesWithCountry();
    mockResponseForCode("en", enResponse);
    mockResponseForCode("en_US", enUsResponse);

    Properties props = messageSource.getAllProperties(Locale.US);

    // en_US key1 overrides en key1
    assertEquals("en_US-key1", props.getProperty("key1"));
    // key2 comes from en only
    assertEquals("en-key2", props.getProperty("key2"));
    // key3 comes from en_US only
    assertEquals("en_US-key3", props.getProperty("key3"));

    // Verify first phase, then reset the mock server so new expectations can be registered
    mockServer.verify();
    mockServer.reset();

    // After timeout, both caches expire and are re-fetched independently with their
    // own timestamps; the delta query filters by the previous load time.
    messageSource.setMaxAgeMilis(100);
    Thread.sleep(150);

    // loadCodes is already cached – no further language list request expected.
    // Only the two translation endpoints are hit again (delta load).
    mockResponseForCode("en", enResponseAfterTimeout);
    mockResponseForCode("en_US", enUsResponseAfterTimeout);

    props = messageSource.getAllProperties(Locale.US);

    // en-key2 was updated in the delta response
    assertEquals("en-key2-updated", props.getProperty("key2"));
    // en_US delta returned nothing new – key1 and key3 still come from previous cache
    assertEquals("en_US-key1", props.getProperty("key1"));
    assertEquals("en_US-key3", props.getProperty("key3"));
  }

}
