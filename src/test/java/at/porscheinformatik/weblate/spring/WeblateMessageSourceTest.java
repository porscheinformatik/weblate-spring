package at.porscheinformatik.weblate.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.stream.Stream;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WeblateMessageSourceTest {

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

    mockServer.expect(ExpectedCount.once(),
      requestTo("http://localhost:8080/api/projects/test-project/languages/")
    ).andRespond(
      withStatus(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body("[{\"code\":\"en\"}]")
    );
  }

  @Test
  void simpleCase() throws URISyntaxException {
    mockServer.expect(ExpectedCount.once(),
        requestTo(new URI("http://localhost:8080/api/translations/test-project/test-comp/en/file/?q=state%3A%3E%3Dtranslated")))
      .andExpect(method(HttpMethod.GET))
      .andRespond(withStatus(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
        .body("key1=Hello, World!\nkey2=Wow this works")
      );

    String key1Value = messageSource.getAllProperties(Locale.ENGLISH).getProperty("key1");

    assertEquals("Hello, World!", key1Value);
    mockServer.verify();
  }

  @Test
  void emptyResponse() throws URISyntaxException {
    mockServer.expect(ExpectedCount.once(),
        requestTo(new URI("http://localhost:8080/api/translations/test-project/test-comp/en/file/?q=state%3A%3E%3Dtranslated")))
      .andExpect(method(HttpMethod.GET))
      .andRespond(withStatus(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
      );

    String key1Value = messageSource.getAllProperties(Locale.ENGLISH).getProperty("key1");

    assertNull(key1Value);
    mockServer.verify();
  }

  @ParameterizedTest
  @MethodSource("validCodesProvider")
  void validCodesToLocale(String code, Locale locale) {
    assertEquals(locale, WeblateMessageSource.deriveLocaleFromCode(code));
  }

  static Stream<Arguments> validCodesProvider() {
    return Stream.of(
      arguments("de", Locale.GERMAN),
      arguments("en_GB", Locale.forLanguageTag("en-GB")),
      arguments("cz_hanz_ad", Locale.forLanguageTag("cz-hanz-ad")),
      arguments("en_devel", Locale.forLanguageTag("en-devel")),
      arguments("en_GB@test", Locale.forLanguageTag("en-gb-x-lvariant-test")),
      arguments("en_Cyri_UK@test", Locale.forLanguageTag("en-Cyri-UK-x-lvariant-test")),
      arguments("en_Cyri_UK@A", Locale.forLanguageTag("en-Cyri-UK-x-lvariant-A"))
    );
  }

  @Test
  void registerCodes() {
    // codes "myalias" and "foo_baz" should be only available after they got registered
    assertNull(messageSource.extractLocale(singletonMap("code", "myalias")));
    assertNull(messageSource.extractLocale(singletonMap("code", "foo_baz")));

    messageSource.registerLocaleMapping("myalias", Locale.forLanguageTag("de-DE-myalias"));
    messageSource.registerLocaleMapping("foo_baz", Locale.forLanguageTag("fo-BA-foobar"));

    assertEquals(Locale.forLanguageTag("de-DE-myalias"), messageSource.extractLocale(singletonMap("code", "myalias")));
  }
}
