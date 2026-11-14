# Google and Microsoft OAuth setup

OAuth lets each user connect Google Calendar, Google Tasks, Outlook calendars and
Microsoft To Do under **My Account → Internet accounts**. Users authorize their
own accounts, then save calendar and task-list selections separately. Dagsverk
sign-in remains separate; email is not synchronized.

Administrators choose how the application is registered under
**Administration → Internet accounts → OAuth application settings**:

- **Direct registration**: you create the Google OAuth client and/or Microsoft
  app registration for your Dagsverk installation. Use this for self-hosting.
- **Managed OAuth service**: a service operator maintains the registrations for
  several installations. The app registrations are shared; user accounts and
  permissions remain individual. This requires an available, configured service.

Both modes support calendars, tasks or both. Calendars and Microsoft To Do are
read-only. Google Tasks defaults to import-only; two-way access requires explicit
write consent and review of outgoing changes.

## Direct

1. Set the exact HTTPS address, including any non-default port, in
   **Administration → General settings** and restart every server instance.
2. Choose **Direct registration** in **OAuth application settings**. Copy the
   displayed redirect URI, for example
   `https://nas.example-tailnet.ts.net:8443/web/task-provider-callback.html`.
3. Complete the Google and/or Microsoft setup below with that URI. Enter each
   client ID and client secret in Dagsverk, save with your administrator password,
   to apply them immediately on every instance. A blank secret field keeps the saved secret.
4. Select **Check setup**, then [test the connection](#verify-the-connection).

### Google

Use the same [Google Cloud project](https://console.cloud.google.com/projectselector2/home/dashboard)
throughout these steps:

1. Enable the [Google Calendar API](https://console.cloud.google.com/apis/library/calendar-json.googleapis.com)
   and/or [Google Tasks API](https://console.cloud.google.com/apis/library/tasks.googleapis.com)
   for the services you want to connect.
2. In **Google Auth Platform**, configure [Branding](https://console.cloud.google.com/auth/branding)
   and [Audience](https://console.cloud.google.com/auth/audience). Choose
   **External** for personal Google accounts or users outside your organization.
   While publishing status is **Testing**, add the intended accounts as test users.
3. In [Data Access](https://console.cloud.google.com/auth/scopes), select
   **Add or remove scopes** and check the rows below for the services you offer.
   Click **Update**, then **Save**. Google's verification requirements apply when
   publishing the app.
4. In [Clients](https://console.cloud.google.com/auth/clients), select
   **Create client → Web application**. Add the Dagsverk URI under
   **Authorized redirect URIs**, then copy the **Client ID** and **Client secret**.

Select the three identity rows, plus the calendar/task rows you need. The labels
below match Google's **Scope** column; `…` abbreviates `https://www.googleapis.com`.
The identity rows may have a blank **API** column.

| Scope shown in Google's table | Access |
| --- | --- |
| `…/auth/userinfo.email` | Account email address |
| `…/auth/userinfo.profile` | Basic account profile |
| `openid` | Account identity |
| `…/auth/calendar.readonly` | Calendars |
| `…/auth/tasks.readonly` | Tasks, import-only |
| `…/auth/tasks` | Tasks, optional two-way access |

For calendar and task import, select the identity rows, `…/auth/calendar.readonly`
and `…/auth/tasks.readonly`. Two-way tasks additionally need `…/auth/tasks`;
keep `…/auth/tasks.readonly` for import-only connections.
Google lists the identity scopes separately; Dagsverk requests them as
`openid email profile`. See Google's [scope reference](https://developers.google.com/identity/protocols/oauth2/scopes#oauth2).

Dagsverk requests only the selected services, with offline access for background
refresh. See Google's [consent setup](https://developers.google.com/workspace/guides/configure-oauth-consent)
and [web-server OAuth flow](https://developers.google.com/identity/protocols/oauth2/web-server).

### Microsoft

1. Open [Microsoft Entra admin center → App registrations](https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)
   and select **New registration**.
2. Under **Supported account types**, select **Any Entra ID Tenant + Personal
   Microsoft accounts** (also labelled **Accounts in any organizational directory
   and personal Microsoft accounts**). This supports Microsoft 365 work or school
   accounts and personal accounts such as Outlook.com.
3. Under **Authentication**, add a **Web** redirect URI using the Dagsverk URI.
4. Under **API permissions → Add a permission → Microsoft Graph → Delegated
   permissions**, add `User.Read`, plus `Calendars.Read` and/or `Tasks.Read`.
   Dagsverk also requests `openid offline_access`. Tenant policies may require
   administrator consent.
5. Copy the **Application (client) ID** from **Overview**. Under
   **Certificates & secrets → Client secrets → New client secret**, create a
   secret and copy its **Value**; the secret ID cannot be used as a credential.
   Replace the secret in Dagsverk before it expires.

Microsoft guides: [register an app](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app),
[add a redirect URI](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-redirect-uri),
[configure API permissions](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-configure-app-access-web-apis#add-permissions-to-access-microsoft-graph)
and [add a client secret](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-credentials#add-a-client-secret).

## Managed

**Managed OAuth service** is the default mode, but a service operator must first
deploy it and configure its Google and Microsoft registrations. Use direct
registration until a working service is available. This repository does not
deploy the service automatically.

Set the installation's HTTPS address as above. Select **Check setup** to find the
service and its provider redirect URIs. The operator follows the same
[Google](#google) and [Microsoft](#microsoft) steps with these service URIs:

| Provider | Registered redirect URI |
| --- | --- |
| Google | `https://SERVICE_HOST/oauth/google/callback` |
| Microsoft | `https://SERVICE_HOST/oauth/microsoft/callback` |

The operator stores client credentials in the service; individual installations
need no client ID or secret. The installation's `/web/task-provider-callback.html`
is the final browser return, separate from these provider redirects.
See [OAuth service operations](CONNECT-SERVICE.md) for hosting, discovery and backups.

## Verify the connection

**Check setup** checks active local settings or service availability and configured
registrations. OAuth settings apply immediately on every instance; changing the
public HTTPS address still requires a coordinated restart.

Under **Verify configuration**, select Calendar and/or Tasks access, then choose
**Verify with Google** or **Verify with Microsoft**. Sign in with a test account
and consent. The result checks the active configuration, sign-in, background access
and each selected API. Optional Google Tasks editing checks the granted permission
without changing a task. No Dagsverk connection is created and nothing is imported.
Temporary managed-service grants are queued for deletion after the check.

If the provider stops before returning to Dagsverk, check the exact redirect URI
and consent audience/test users. Review any failed check, correct the setup and
start verification again. For
Google, enable Calendar API and Tasks API for the services selected; use the scopes
listed above. For Microsoft, check the delegated permissions and tenant policy.

In **My Account → Internet accounts**, connect a test Google or Microsoft account.
Test calendars, tasks and combined access as applicable; save selections, review
the first task import, and verify refresh, disconnect and reconnect. Test personal
Microsoft and Microsoft 365 accounts if both will be used. Switching modes requires
explicit reauthorization; existing connections keep their original refresh path.

## Private HTTPS addresses

A private [Tailscale](TAILSCALE.md) hostname can be used when the provider accepts
the exact URI and the browser can reach it over the VPN with a trusted certificate.
Include the port; do not add wildcards, queries, fragments or a trailing slash.
The managed service needs no inbound connection to the installation.
Open Dagsverk at that same HTTPS address when connecting. Changing it requires
updating registrations and reconnecting.
See [Google's redirect rules](https://developers.google.com/identity/protocols/oauth2/web-server#uri-validation)
and [Microsoft's redirect rules](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url).

For first-boot environment settings and retained registration files, see
[task-provider configuration](TASK-PROVIDERS.md#retain-operator-settings) and
[configuration upgrades](CONFIGURATION.md). Settings saved in Administration
take precedence over environment defaults and are retained by protected instance
backups, but excluded from portable account archives. Authentication recovery
preserves provider settings and secrets.
