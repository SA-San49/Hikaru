# Hikaru Privacy Policy

**Effective date: November 5, 2026**

Hikaru is an open-source Android application and website. This policy explains what happens to information when you use the official Hikaru app or visit the Hikaru website.

## In brief

**Hikaru does not operate a user-account service or central app server, and the current official Android builds are configured without Firebase Analytics or Firebase Crashlytics. The Hikaru project does not directly collect or store personal information from app users.**

The app does make network connections to sources and services that you choose to use. Those independent providers may receive information needed to handle your requests, and their own privacy policies apply. “Hikaru does not directly collect data” does not mean that using the app involves no third-party data processing.

## Information handled on your device

The app stores app data locally on your device, such as settings, your library, watch progress, downloaded media, and any account or integration details you choose to configure. This information is under your control on the device. The Hikaru project does not receive it through a Hikaru-operated account or synchronization server.

If you use an optional backup, tracking, sync, AI, or other connected feature, information you choose to submit is sent to the provider you selected so that feature can work. Review that provider’s privacy terms before connecting an account or sharing information.

## Diagnostics and analytics in Android builds

The source code contains optional Firebase Analytics and Crashlytics integration. The project’s current official release and preview build workflows explicitly build with telemetry disabled (`-Pinclude-telemetry=false`), so those official builds exclude that telemetry implementation. The official builds therefore do not send app analytics or crash reports to Hikaru through Firebase.

Builds made by third parties or built from source with different options may behave differently. Check the source and settings for the particular build you install. Local app statistics and diagnostics displayed on your device are not automatically sent to Hikaru just because they are shown in the app.

## Sources, extensions, and other third parties

Hikaru can connect to third-party anime and media sources through extensions, and to tracking or other services when you configure them. When you browse, search, stream, download, sign in, or synchronize through one of those providers, that provider may receive your IP address, request details, account information, or other data needed to respond. Extensions and service providers are not operated by the Hikaru project; their handling of data is governed by their own policies and terms.

Hikaru does not control the availability, security, or privacy practices of these external services. You can avoid a particular provider by not installing or using its extension or integration.

## Hikaru website

The Hikaru website is a static site hosted by GitHub Pages. It does not include Hikaru-operated analytics, advertising, account registration, or contact forms. When you visit, GitHub may process technical request information as part of providing and protecting its hosting service. GitHub’s own privacy terms govern that processing.

## Sharing, sale, and retention

The Hikaru project does not sell personal information and does not maintain a central user database. Information kept locally in the app remains on your device unless you choose to send it to an external source, service, or backup destination. External providers determine their own use and retention of information they receive.

## Your choices

- Choose which sources, extensions, and integrations to install or use.
- Do not connect third-party accounts or submit information to a provider if you do not agree with its terms.
- Manage or remove app data using the app and your Android device settings.
- Review the privacy policy of any external provider you use.

## Changes to this policy

This policy may be updated when the website or app’s data practices change. The effective date above will be revised when the text is updated. The canonical source is the repository-root [`policy.md`](https://github.com/SA-San49/Hikaru/blob/main/policy.md); the website’s `/privacy/` page is generated from this file during each site build.

## Contact

For questions or corrections about this policy, open a discussion or issue in the [Hikaru GitHub repository](https://github.com/SA-San49/Hikaru/issues). Do not post sensitive personal information in a public issue.
