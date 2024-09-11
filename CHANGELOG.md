# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.9.0] - (https://github.com/porscheinformatik/weblate-spring/compare/v0.8.0...v0.9.0) - 2024-09-11

### BREAKING CHANGES

- Java 17 and Spring Boot 3.x is now required

### Dependencies

- Spring Boot 3 (#82) and switch to current Spring Web API (#83)
- Update all Maven plugins (#84)

## [0.8.0] - (https://github.com/porscheinformatik/weblate-spring/compare/v0.7.1...v0.8.0) - 2023-01-14

### BREAKING CHANGES

- Java 11 is now required

### Features

- Add possibility to load data asynchronously (#49, #58)
- API compatability for Spring Framework 6 / Spring Boot 3 (#59, #60)

## [0.7.1](https://github.com/porscheinformatik/weblate-spring/compare/v0.7.0...v0.7.1) - 2022-12-07

- Reuse cached entries if possible (#55)
- Fix small code and spell issues (#56)

## [0.7.0](https://github.com/porscheinformatik/weblate-spring/compare/v0.6.0...v0.7.0) - 2022-11-23

### Features

- Cache handling improvements (#48)
  - Added a method to reload the available languages without clearing the cache.
  - Added a method to remove empty cache entries.
  - Set only languages with translations as available.
  - Improved logging when translations are not available: only report when no translations are available at all.

### Dependencies

- Spring Boot 2.7.5 (#47)

## [0.6.0](https://github.com/porscheinformatik/weblate-spring/compare/v0.5.1...v0.6.0) - 2022-10-12

### Features

- Allow to specify the initial cache timestamp for bundled messages (#46)

### Dependencies

- Spring Boot 2.7.4

## [0.5.1](https://github.com/porscheinformatik/weblate-spring/compare/v0.5.0...v0.5.1) - 2022-09-16

### Fixed

- Unchanged texts were not loaded (#43)

## [0.5.0](https://github.com/porscheinformatik/weblate-spring/compare/v0.4.0...v0.5.0) - 2022-09-01

### BREAKING CHANGES

- Switch to units API from file API (#40). This fixes the issue that the GitLab API performs a commit when fetching data via the file API (#38).

### Dependencies

- Spring Boot 2.7.3

## [0.4.0](https://github.com/porscheinformatik/weblate-spring/compare/v0.3.0...v0.4.1) - 2022-03-30

### Features

- Added support for Locale variants (#20)
- Add public getter for field existingLocales in WeblateMessageSource (#32)

### Dependencies

- Spring Boot 2.6.5

## [0.3.1](https://github.com/porscheinformatik/weblate-spring/compare/v0.3.0...v0.3.1) - 2021-11-29

### Bug Fixes

- NPE when no texts are translated (#15)

## [0.3.0](https://github.com/porscheinformatik/weblate-spring/compare/v0.2.1...v0.3.0) - 2021-11-26

### Features

- Feat: add the possibility to filter contents with query (#14)

### Dependencies

- Spring Boot 2.5.7

### Bug Fixes

- Untranslated texts are not loaded as empty which enables a fallback to the default language.

## [0.2.1](https://github.com/porscheinformatik/weblate-spring/compare/v0.2.0...v0.2.1) - 2021-09-07

### Bug Fixes

- Fix: add trailing slash to file download (is needed by Weblate API), #9

## [0.2.0](https://github.com/porscheinformatik/weblate-spring/compare/v0.1.0...v0.2.0) - 2021-08-04

### Dependencies

- Spring Boot 2.5.3

## [0.1.0](https://github.com/porscheinformatik/weblate-spring/tree/v0.1.0) - 2021-02-01

### Features

- Basic implementation for Weblate Spring integration

[0.1.0]: https://github.com/porscheinformatik/weblate-spring/releases/tag/v0.1.0
