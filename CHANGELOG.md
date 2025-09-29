# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- (new features for the upcoming version go here)

### Changed
- (behavioral changes go here)

### Fixed
- (bug fixes go here)

---

## [1.7.0] - 2025-09-29
### Changed
- New application update procedure

### Fixed
- Some minor fixes


## [1.6.0] - 2025-09-26

### Added
- Exit is a long press on a square ☉ button on the main screen.
- Asks for POST_NOTIFICATIONS on first run (Android 13+).

### Changed
- Android 13/14+ compatibility:
- Mute/Unmute is a regular tap (with a tiny debounce to ignore micro-taps).

---

## [1.5.0] - 2025-09-24

### Added
- Add Exit action (notification + menu) and consistent app exit

### Changed
- mic level probe + guard timer; resilient indicator

### Fixed
- restore stable audio path; simplify capture routing
- correct status texts (running/stopped) + activity sync

## [1.4.0] - 2025-09-19

### Fixed
- fix big bug with no sound on hotspot


## [1.3.0] - 2025-09-18
### Added
- Start/Stop button back again! Now - need long tap.

## [1.2.0] - 2025-09-18
### Added
- Added Network monitor;
- Added network type bage to status
- Check for manual update + Auto update (once at week)

### Changed
- Break hardcoded server port 8080 - now auto select a free port from 8080

## [1.1.0] - 2025-09-17

### Added
- EN lang in web client;

### Fixed
- fix bug when Activity crashes when screen is rotated
- Several minor fixes

## [1.0.0] - 2025-09-12
### Added
- First release
