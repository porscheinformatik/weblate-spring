package at.porscheinformatik.weblate.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WeblateMessageSourceTest {

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
      URI uri = new URI("http://localhost:8080/api/translations/test-project/test-comp/en/file/?q=state%3A%3E%3Dtranslated");
      DefaultResponseCreator response = withStatus(HttpStatus.OK).contentType(MediaType.TEXT_PLAIN);
      if (body != null) {
        response.body(body);
      }      
      mockServer.expect(ExpectedCount.once(),
          requestTo(uri))
          .andExpect(method(HttpMethod.GET))
          .andRespond(response);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void simpleCase() {
    mockGetLocales();
    mockResponse("key1=Hello, World!\nkey2=Wow this works");

    String key1Value = messageSource.getAllProperties(Locale.ENGLISH).getProperty("key1");

    assertEquals("Hello, World!", key1Value);
    mockServer.verify();
  }

  @Test
  void emptyResponse() throws URISyntaxException {
    mockGetLocales();
    mockResponse(null);

    String key1Value = messageSource.getAllProperties(Locale.ENGLISH).getProperty("key1");

    assertNull(key1Value);
    mockServer.verify();
  }

  @Test
  void clearCache() {
    mockGetLocales();
    mockResponse("key1=Hello, World!\nkey2=Wow this works");
    mockGetLocales();
    mockResponse("key1=Another one\nkey2=Wow this works");

    Properties allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals("Hello, World!", allProperties.get("key1"));
    messageSource.clearCache();
    allProperties = messageSource.getAllProperties(Locale.ENGLISH);
    assertEquals("Another one", allProperties.get("key1"));
  }

}
