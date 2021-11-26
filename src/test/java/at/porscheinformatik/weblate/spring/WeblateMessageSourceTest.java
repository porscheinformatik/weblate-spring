package at.porscheinformatik.weblate.spring;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

public class WeblateMessageSourceTest {

  private RestTemplate restTemplate = new RestTemplate();

  private MockRestServiceServer mockServer;

  @Before
  public void init() {
    mockServer = MockRestServiceServer.createServer(restTemplate);
  }

  @Test
  public void simpleCase() throws URISyntaxException {
    WeblateMessageSource messageSource = new WeblateMessageSource();
    messageSource.setRestTemplate(restTemplate);
    messageSource.setProject("test-project");
    messageSource.setComponent("test-comp");
    messageSource.setBaseUrl("http://localhost:8080");

    mockServer.expect(ExpectedCount.once(),
      requestTo("http://localhost:8080/api/projects/test-project/languages/")
    ).andRespond(
      withStatus(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body("[{\"code\":\"en\"}]")
    );

    mockServer.expect(ExpectedCount.once(),
        requestTo(new URI("http://localhost:8080/api/translations/test-project/test-comp/en/file/?q=state%3A%3E%3Dtranslated")))
      .andExpect(method(HttpMethod.GET))
      .andRespond(withStatus(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
        .body("key1=Hello, World!\nkey2=Wow this works")
      );



    assertEquals("Hello, World!", messageSource.getAllProperties(Locale.ENGLISH).getProperty("key1"));
    mockServer.verify();
  }

}
