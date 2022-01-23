# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 2022-01-24

### Added

- Added support for Locale variants (#20)

### Changed

- Dependency Updates:
  - Spring Boot 2.6.3

## [0.3.1] - 2021-11-29 

### Fixed

- NPE when no texts are translated (#15)

## [0.3.0] - 2021-11-26

### Added

- Feat: add the possibility to filter contents with query (#14)

### Changed

- Dependency Updates:
  - Spring Boot 2.5.7

### Fixed

- Untranslated texts are not loaded as empty which enables a fallback to the default language.

## [0.2.1] - 2021-09-07

### Fixed

- Fix: add trailing slash to file download (is needed by Weblate API), #9

## [0.2.0] - 2021-08-04

### Changed

- Dependency Updates:
  - Spring Boot 2.5.3

## [0.1.0] - 2021-02-01

### Added

- Basic implementation for Weblate Spring integration

[0.1.0]: https://github.com/porscheinformatik/weblate-spring/releases/tag/v0.1.0
