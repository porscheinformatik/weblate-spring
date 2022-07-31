package at.porscheinformatik.weblate.spring;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestTemplate;

import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WeblateMessageSourceTest {

  private static final String RESPONSE_OK = "{\"count\":2,\"next\":null,\"previous\":null,\"results\":["
      + "{\"id\":1,\"context\":\"key1\",\"source\":[\"Hello, World!\"],\"target\":[\"Hello, World!\"]},"
      + "{\"id\":2,\"context\":\"key2\",\"source\":[\"Wow this works\"],\"target\":[\"Wow this works\"]}"
      + "]}";
  private static final String RESPONSE_PAGING = "{\"count\":2,\"next\":\"http://localhost:8080/api/translations/test-project/test-comp/en/units/?page=1&q=state%3A%3E%3Dtranslated\",\"previous\":null,\"results\":[]}";
  private static final String RESPONSE_EMPTY = "{\"count\":0,\"next\":null,\"previous\":null,\"results\":[]}";

  private RestTemplate restTemplate = new RestTemplate();

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
    mockServer.expect(ExpectedCount.once(),
      requestTo("http://localhost:8080/api/projects/test-project/languages/")
    ).andRespond(
      withStatus(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body("[{\"code\":\"en\"}]")
    );
  }

  private void mockResponse(String body) {
    try {
      String url = "http://localhost:8080/api/translations/test-project/test-comp/en/units/";
      DefaultResponseCreator response = withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON);
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
  void simpleCase() {
    mockGetLocales();
    mockResponse(RESPONSE_OK);
    mockResponse(RESPONSE_OK);

    String key1Value = messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH);
    assertEquals("Hello, World!", key1Value);
    key1Value = messageSource.resolveCodeWithoutArguments("key1", Locale.US);
    assertEquals("Hello, World!", key1Value);
    key1Value = messageSource.resolveCodeWithoutArguments("key1", Locale.US);
    assertEquals("Hello, World!", key1Value);
  }

  @Test
  void emptyResponse() throws URISyntaxException {
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
    mockResponse(RESPONSE_OK.replace("Hello, World!", "Another one"));

    Properties allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals("Hello, World!", allProperties.get("key1"));
    messageSource.clearCache();
    allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals("Another one", allProperties.get("key1"));
  }

  @Test
  void paging() {
    mockGetLocales();
    mockResponse(RESPONSE_PAGING);
    mockResponse(RESPONSE_PAGING); // try a second paging call
    mockResponse(RESPONSE_OK);
    
    Properties allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals("Hello, World!", allProperties.get("key1"));
  }

  @Test
  void cacheTimeout() throws Exception {
    mockGetLocales();
    mockResponse(RESPONSE_OK);
    mockResponse(RESPONSE_OK.replace("Hello, World!", "Another one"));

    assertEquals("Hello, World!", messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
    assertEquals("Hello, World!", messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
    
    messageSource.setMaxAgeMilis(1000);
    Thread.sleep(1001);
    assertEquals("Another one", messageSource.resolveCodeWithoutArguments("key1", Locale.ENGLISH));
  }

}
