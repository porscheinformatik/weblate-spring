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

}
