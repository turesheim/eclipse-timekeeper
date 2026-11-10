# Password replacement using a passkey

Synthetic accounts captured by the packaged-server HTTPS Playwright tests in
`server/web/tests/passkeys.spec.js`, using Chromium's virtual WebAuthn authenticator.
Source: `07e250ba96b93db420d9c829aee7189486203b29`.

The English captures show the retained password draft after canceling a native
passkey prompt. The Norwegian captures show empty forms with passkey confirmation
selected. Password replacement requires another explicit submission and fresh
passkey verification. No live account, provider or authenticator is used.

| Language / theme | Desktop | Narrow screen |
| --- | --- | --- |
| English / light | [1280 pixels](passkey-password-desktop.png) | [390 pixels](passkey-password-mobile.png) |
| Norwegian / light | [1440 pixels](passkey-password-nb-timekeeper-1440.png) | [390 pixels](passkey-password-nb-timekeeper-390.png) |
| Norwegian / dark | [1440 pixels](passkey-password-nb-timekeeper-dark-1440.png) | [390 pixels](passkey-password-nb-timekeeper-dark-390.png) |

The same scenarios also check Norwegian light/dark overflow and axe accessibility
at 320, 390 and 1440 pixels. The administrator scenario verifies cancellation,
password replacement without the old password, subsequent password sign-in,
retained administrator access and subsequent passkey sign-in.
