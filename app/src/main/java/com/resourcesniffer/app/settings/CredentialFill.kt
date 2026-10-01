package com.resourcesniffer.app.settings

import java.net.URI
import org.json.JSONObject

internal fun credentialOrigin(url: String): String? = runCatching {
    val uri = URI(url)
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null)
    "https://${uri.host.lowercase()}" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
}.getOrNull()

internal fun credentialFillScript(account: WebsiteAccount): String {
    val origin = credentialOrigin(account.url) ?: error("帳密帶入僅支援 HTTPS 網站")
    val data = JSONObject().put("origin", origin).put("username", account.username).put("password", account.password).toString()
    return """
        (() => {
          const saved = $data;
          if (window.top !== window || location.origin !== saved.origin) return 'origin-mismatch';
          const visible = el => !el.disabled && !el.readOnly && el.getClientRects().length > 0;
          const passwords = [...document.querySelectorAll('input[type="password"]')].filter(visible);
          // Do not fill account-creation or change-password forms.
          if (passwords.length > 1 || passwords.some(el => el.autocomplete === 'new-password')) return 'ambiguous';
          const scope = passwords[0]?.form || document;
          const users = [...scope.querySelectorAll('input[autocomplete="username"],input[type="email"],input[name="username"],input[name="email"],input[name="login"],input[id="username"]')].filter(visible);
          const user = users[0] || (passwords.length === 1 ? [...scope.querySelectorAll('input[type="text"]')].filter(visible)[0] : null);
          const set = (el, value) => {
            if (!el || !value) return false;
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, value);
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return true;
          };
          const u = set(user, saved.username);
          const p = set(passwords[0], saved.password);
          return u || p ? 'filled' : 'no-fields';
        })()
    """.trimIndent()
}
