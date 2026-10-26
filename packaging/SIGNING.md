# Signing the installers

`.github/workflows/cli-release.yml` builds unsigned installers until the secrets and variables
below exist, then signs automatically. Set them under **Settings → Secrets and variables →
Actions** (or with `gh secret set` / `gh variable set`).

## macOS: Developer ID + notarization

Removes the "Apple could not verify" block on the `.pkg`.

1. Join the [Apple Developer Program](https://developer.apple.com/programs/) ($99/year). Note
   your **Team ID** (Membership details, 10 characters).
2. In Keychain Access → Certificate Assistant → *Request a Certificate From a Certificate
   Authority*, save a CSR to disk. At
   [Certificates](https://developer.apple.com/account/resources/certificates/list), create a
   **Developer ID Application** and a **Developer ID Installer** certificate from it (the
   Account Holder has to do this). Download both and double-click to install.
3. In Keychain Access → *My Certificates*, select both certificates, *Export 2 items…* as
   `certs.p12` with a password.
4. At [account.apple.com](https://account.apple.com) → Sign-In and Security → *App-Specific
   Passwords*, create one for notarization.

| Name | Kind | Value |
|---|---|---|
| `MACOS_CERTIFICATES_P12` | secret | `base64 -i certs.p12 \| pbcopy` |
| `MACOS_CERTIFICATES_PASSWORD` | secret | the `.p12` export password |
| `APPLE_ID` | secret | your Apple ID email |
| `APPLE_APP_PASSWORD` | secret | the app-specific password |
| `APPLE_TEAM_ID` | variable | e.g. `AB12CD34EF` (turns signing on) |
| `APPLE_SIGNING_NAME` | variable | what follows `Developer ID Application: ` in the certificate name, e.g. `Jane Doe (AB12CD34EF)` |

Verify with a trial run (Actions → CLI Release → *Run workflow*): the *Notarize and staple*
step should end with `source=Notarized Developer ID`.

## Windows: SignPath Foundation

Removes the SmartScreen "unrecognized app" prompt over time. The publisher shown is "SignPath
Foundation", and SmartScreen reputation builds up with downloads, so the first releases may
still get a warning.

1. Apply at [signpath.org](https://signpath.org) (free for open-source projects). Their terms
   require a code signing policy and the attribution line *"Free code signing provided by
   SignPath.io, certificate by SignPath Foundation"* in the README; add both once approved.
2. In SignPath, add this repository as a trusted build system (GitHub.com) and create an
   artifact configuration with:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
     <zip-file>
       <msi-file path="sophi-windows-x64.msi" />
     </zip-file>
   </artifact-configuration>
   ```

3. Create an API token for a CI user that may submit to the project.

| Name | Kind | Value |
|---|---|---|
| `SIGNPATH_API_TOKEN` | secret | the API token |
| `SIGNPATH_ORGANIZATION_ID` | variable | organization ID from SignPath (turns signing on) |
| `SIGNPATH_PROJECT_SLUG` | variable | e.g. `SoPhi` |
| `SIGNPATH_SIGNING_POLICY_SLUG` | variable | `release-signing` |

Only tag builds are signed. Each one waits (up to an hour) for you to approve the request in
SignPath.
