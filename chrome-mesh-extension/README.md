# Mesh GraphiQL Links

Open a GraphQL query from Java in a new editor tab on the existing Mesh GraphiQL page. The extension fills the query and variables, then runs the query automatically. It makes no backend changes and needs no build or dependencies.

## Install

1. In Chrome, open `chrome://extensions` and enable **Developer mode**.
2. Choose **Load unpacked** and select `C:\Users\al\projects\mesh-graphiql-extension`.
3. Sign in to the Mesh site normally, then reload any existing GraphiQL page.
4. Open an example in that Chrome profile:

   <https://ui.mesh-java.test.ecp/graphiql/index.html?query=query%20FromJava%20%7B%20__typename%20%7D>
   or
   <https://ui.mesh-java.uat.ecp/graphiql/index.html?query=query%20FromJava%20%7B%20__typename%20%7D>

The extension operates on `https://ui.mesh-java.test.ecp/graphiql/*` and `https://ui.mesh-java.uat.ecp/graphiql/*`. Chrome's in-app counterpart in Codex does not load this Chrome extension.
After updating an existing installation, click **Reload** for the extension in `chrome://extensions` and accept the Mesh host permission if Chrome asks.

## Link format

```text
https://ui.mesh-java.<test|uat>.ecp/graphiql/index.html?query=<encoded GraphQL>&variables=<encoded JSON object>
```

Encode each value separately using UTF-8 form/URL encoding. `variables` is optional and defaults to `{}`. The query's operation name supplies the normal GraphiQL tab title. Do not put the payload on `/graphql` or `/graphiql/graphql`: these are API paths.

Chrome redirects these `?query=` page links to `#query=` before the page request reaches Nginx. The fragment stays in the browser, so the server receives only `/graphiql/index.html`. After GraphiQL loads, the script validates the document, adds a tab, and uses the existing CodeMirror editor API to populate it, preserving other editor tabs. It then clicks GraphiQL's Execute button. Automatic execution accepts exactly one GraphQL query operation; mutations, subscriptions, and invalid input show a message without running. A successfully consumed payload is removed from the current URL so refreshing does not import or run it again.

Without the extension's redirect rule, URL query parameters are sent to the Mesh site and long links can trigger Nginx 414. The original link may still appear in browser history. GraphiQL normally saves editor contents locally. Use links for ordinary queries, without credentials. If sign-in redirects lose the parameters, sign in first and reopen the original link.

## Java 11+

See [examples/OpenMeshQuery.java](examples/OpenMeshQuery.java). To print a sample URL (defaults to test, or pass `--uat`):

```powershell
java examples/OpenMeshQuery.java --print
java examples/OpenMeshQuery.java --uat --print
```

To open it in your default desktop browser:

```powershell
java examples/OpenMeshQuery.java
java examples/OpenMeshQuery.java --uat
```

Chrome with the extension installed must be your default browser for this example. In an existing Java app, call `OpenMeshQuery.queryUrl(query, variablesJson)` (or `OpenMeshQuery.queryUrl(base, query, variablesJson)`) and use your existing browser launcher. `Desktop.browse` runs on the machine running Java; server-side Java should return the URL to its client instead.

This version imports into the browser tab opened by the link. It does not find and focus another existing Chrome tab.

## Verify

```powershell
node test.mjs
```

For a local browser check using the existing GraphiQL assets (read only):

```powershell
node smoke-server.mjs C:\Users\al\projects\graph-mesh-debug\config\graphiql
```

Open `http://127.0.0.1:8765/graphiql/` and add a named query to an editor tab. Then open `http://127.0.0.1:8765/graphiql/index.html#query=query%20Imported%20%7B%20__typename%20%7D` and confirm both tabs remain. The local server should log `NON-INTROSPECTION` once, proving the new query ran. Refresh and confirm it does not run again. The local server loads the exact content script as page JavaScript, exercising the MAIN-world editor integration. It returns an offline schema error and never contacts Mesh. This checks the page script, not Chrome's request redirect rule or extension installation.

The extension targets the current GraphiQL/CodeMirror 5 interface. An editor upgrade may require updating selectors or the editor API in `content.js`.
