// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

/**
 * Browser-like JavaScript host polyfill injected into QuickJS before running Nuvio scrapers:
 * - fetch(url, options) -> Promise<{ ok, status, statusText, headers, url, text(), json() }>
 * - cheerio.load(html) -> Jsoup-backed selector API (find, text, attr, html, each, map, first, eq, length)
 * - atob / btoa, console.log/warn/error, setTimeout/clearTimeout, URL & URLSearchParams
 * - __nuvioResult(payload) bridge back to Kotlin
 */
internal object NuvioPolyfill {

    fun build(): String = """
        (function(global) {
          if (global.__kestrelPolyfillLoaded) return;
          global.__kestrelPolyfillLoaded = true;

          global.console = {
            log: function() { __nuvioCall(JSON.stringify({ op: 'log', message: Array.prototype.slice.call(arguments).join(' ') })); },
            warn: function() { __nuvioCall(JSON.stringify({ op: 'log', message: '[WARN] ' + Array.prototype.slice.call(arguments).join(' ') })); },
            error: function() { __nuvioCall(JSON.stringify({ op: 'log', message: '[ERR] ' + Array.prototype.slice.call(arguments).join(' ') })); }
          };

          global.setTimeout = function(fn, ms) {
            var args = Array.prototype.slice.call(arguments, 2);
            Promise.resolve().then(function() {
              return __nuvioSleepAsync(ms || 0);
            }).then(function() {
              if (typeof fn === 'function') fn.apply(null, args);
            });
            return 1;
          };
          global.clearTimeout = function() {};

          global.btoa = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'btoa', value: String(str) })) || '{}');
            return res.value || '';
          };
          global.atob = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'atob', value: String(str) })) || '{}');
            return res.value || '';
          };

          global.fetch = async function(url, opts) {
            opts = opts || {};
            var payload = {
              url: String(url),
              method: (opts.method || 'GET').toUpperCase(),
              headers: opts.headers || {},
              body: opts.body !== undefined && opts.body !== null ? String(opts.body) : null
            };
            var raw = await __nuvioFetchAsync(JSON.stringify(payload));
            var res = JSON.parse(raw);
            if (res.error) throw new Error(res.error);
            var bodyText = res.body || '';
            return {
              ok: res.status >= 200 && res.status < 300,
              status: res.status,
              statusText: res.statusText || '',
              url: res.url || payload.url,
              headers: {
                get: function(name) {
                  var lower = String(name).toLowerCase();
                  var h = res.headers || {};
                  for (var k in h) {
                    if (k.toLowerCase() === lower) return h[k];
                  }
                  return null;
                }
              },
              text: async function() { return bodyText; },
              json: async function() { return JSON.parse(bodyText); }
            };
          };

          function makeSelection(html, selector) {
            var items = JSON.parse(__nuvioCall(JSON.stringify({
              op: 'domSelect',
              html: String(html || ''),
              selector: String(selector || '')
            })) || '[]');

            function wrap(nodes) {
              var sel = {
                length: nodes.length,
                text: function() { return nodes.map(function(n) { return n.text || ''; }).join(''); },
                html: function() { return nodes.length ? (nodes[0].html || '') : ''; },
                attr: function(name) { return nodes.length && nodes[0].attrs ? nodes[0].attrs[name] : undefined; },
                first: function() { return wrap(nodes.length ? [nodes[0]] : []); },
                last: function() { return wrap(nodes.length ? [nodes[nodes.length - 1]] : []); },
                eq: function(i) { return wrap(nodes[i] ? [nodes[i]] : []); },
                find: function(sub) {
                  var combined = [];
                  nodes.forEach(function(n) {
                    var found = JSON.parse(__nuvioCall(JSON.stringify({
                      op: 'domSelect',
                      html: n.outerHtml || n.html || '',
                      selector: String(sub)
                    })) || '[]');
                    combined = combined.concat(found);
                  });
                  return wrap(combined);
                },
                each: function(cb) {
                  nodes.forEach(function(n, idx) { cb.call(wrap([n]), idx, wrap([n])); });
                  return sel;
                },
                map: function(cb) {
                  var out = nodes.map(function(n, idx) { return cb.call(wrap([n]), idx, wrap([n])); });
                  return { get: function() { return out; } };
                },
                toArray: function() { return nodes.map(function(n) { return wrap([n]); }); }
              };
              return sel;
            }
            return wrap(items);
          }

          global.cheerio = {
            load: function(html) {
              var $ = function(selector) {
                if (selector && typeof selector === 'object' && selector.text) return selector;
                return makeSelection(html, selector);
              };
              $.html = function() { return String(html || ''); };
              return $;
            }
          };

          global.__nuvioResult = function(payload) {
            __nuvioCall(JSON.stringify({ op: 'result', data: JSON.stringify(payload) }));
          };

          global.module = { exports: {} };
          global.exports = global.module.exports;
        })(globalThis);
    """.trimIndent()
}
