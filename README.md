# Weblate Spring

Provides a Spring MessageSource backed by a [Weblate](https://weblate.org/) Server.

## Usage

Add the Maven dependency to your project (find the current version
on [Maven Central](https://search.maven.org/search?q=g:at.porscheinformatik.weblate%20AND%20a:weblate-spring&core=gav)):

```xml

<dependency>
  <groupId>at.porscheinformatik.weblate</groupId>
  <artifactId>weblate-spring</artifactId>
  <version>${weblate-spring.version}</version>
</dependency>
```

Then declare the `WeblateMessageSource` as a bean. You can configure the `MessageSource` via:

- baseUrl (required) - the base URL of your Weblate instance
- project (required) - the project slug
- component (required) - the component slug

Usually you might want to have the local message bundles as a backup when Weblate is not running. Therefore, you can
set a `ResourceBundleMessageSource` as the parent of the `WeblateMessageSource`.

`WeblateMessageSource` implements an interface `AllPropertiesSource` which can be used to obtain all translations from
the message source. This is needed when you want to get all your translations to your client (for example in a Single
Page App). The `AllPropertiesReloadableResourceBundleMessageSource` should also be used in this context.

Here is a full example bean configuration for a Spring Boot app (with "messages" as the default bundle):

```java
@Bean
@Primary
public WeblateMessageSource messageSource() {
  var weblateMessageSource = new WeblateMessageSource();
  weblateMessageSource.setBaseUrl("https://hosted.weblate.org");
  weblateMessageSource.setProject("my-project");
  weblateMessageSource.setComponent("my-component");
  weblateMessageSource.useAuthentication("api-key");
  weblateMessageSource.setParentMessageSource(localMessageSource());
  return weblateMessageSource;
}

@Bean
MessageSource localMessageSource() {
  var localMessageSource = new AllPropertiesReloadableResourceBundleMessageSource();
  localMessageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
  localMessageSource.setBasename("messages");
  localMessageSource.setFallbackToSystemLocale(false);
  return localMessageSource;
}
```

## Authentication / Adapting RestTemplate

For the simple authentication via API key you can use `WeblateMessageSource#useAuthentication`. If you want further
customization of the HTTP calls you can set your own `RestTemplate`. 

## Caching

`WeblateMessageSource` caches loaded translations 30 minutes (this can be changed by setting `maxAgeMilis`).
If you want to update one locale immediately call `WeblateMessageSource#reload(Locale)`. 

If you want to fully clear the cache call `WeblateMessageSource#clearCache` - this will also reload the languages. **Please use this with caution as it add heavy load to the Weblate API!**
